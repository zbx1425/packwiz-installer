package link.infra.packwiz.installer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.moandjiezana.toml.Toml;
import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.ManifestFile;
import link.infra.packwiz.installer.metadata.PackFile;
import link.infra.packwiz.installer.metadata.hash.GeneralHashingSource;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashUtils;
import link.infra.packwiz.installer.request.HandlerManager;
import link.infra.packwiz.installer.ui.IOptionDetails;
import link.infra.packwiz.installer.ui.IUserInterface;
import link.infra.packwiz.installer.ui.InstallProgress;
import okio.Buffer;
import okio.Okio;
import okio.Source;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class UpdateManager {

	public final Options opts;
	public final IUserInterface ui;

	public static class Options {
		public URI downloadURI = null;
		public String manifestFile = "packwiz.json"; // TODO: make configurable
		public String packFolder = ".";
		public Side side = Side.CLIENT;

		public static enum Side {
			@SerializedName("client")
			CLIENT("client"), @SerializedName("server")
			SERVER("server"), @SerializedName("both")
			BOTH("both", new Side[] { CLIENT, SERVER });

			private final String sideName;
			private final Side[] depSides;

			Side(String sideName) {
				this.sideName = sideName.toLowerCase();
				this.depSides = null;
			}

			Side(String sideName, Side[] depSides) {
				this.sideName = sideName.toLowerCase();
				this.depSides = depSides;
			}

			@Override
			public String toString() {
				return this.sideName;
			}

			public boolean hasSide(Side tSide) {
				if (this.equals(tSide)) {
					return true;
				}
				if (this.depSides != null) {
					for (Side depSide : this.depSides) {
						if (depSide.equals(tSide)) {
							return true;
						}
					}
				}
				return false;
			}

			public static Side from(String name) {
				String lowerName = name.toLowerCase();
				for (Side side : Side.values()) {
					if (side.sideName.equals(lowerName)) {
						return side;
					}
				}
				return null;
			}
		}
	}

	public UpdateManager(Options opts, IUserInterface ui) {
		this.opts = opts;
		this.ui = ui;
		this.start();
	}

	protected void start() {
		this.checkOptions();

		ui.submitProgress(new InstallProgress("Loading manifest file..."));
		Gson gson = new GsonBuilder().registerTypeAdapter(Hash.class, new Hash.TypeHandler()).create();
		ManifestFile manifest;
		try {
			manifest = gson.fromJson(new FileReader(Paths.get(opts.packFolder, opts.manifestFile).toString()),
					ManifestFile.class);
		} catch (FileNotFoundException e) {
			manifest = new ManifestFile();
		} catch (JsonSyntaxException | JsonIOException e) {
			ui.handleExceptionAndExit(e);
			return;
		}

		ui.submitProgress(new InstallProgress("Loading pack file..."));
		GeneralHashingSource packFileSource;
		try {
			Source src = HandlerManager.getFileSource(opts.downloadURI);
			packFileSource = HashUtils.getHasher("sha256").getHashingSource(src);
		} catch (Exception e) {
			// TODO: still launch the game if updating doesn't work?
			// TODO: ask user if they want to launch the game, exit(1) if they don't
			ui.handleExceptionAndExit(e);
			return;
		}
		PackFile pf;
		try {
			pf = new Toml().read(Okio.buffer(packFileSource).inputStream()).to(PackFile.class);
		} catch (IllegalStateException e) {
			ui.handleExceptionAndExit(e);
			return;
		}

		ui.submitProgress(new InstallProgress("Checking local files..."));

		// Invalidation checking must be done here, as it must happen before pack/index hashes are checked
		List<URI> invalidatedUris = new ArrayList<>();
		if (manifest.cachedFiles != null) {
			for (Map.Entry<URI, ManifestFile.File> entry : manifest.cachedFiles.entrySet()) {
				boolean invalid = false;
				// if isn't optional, or is optional but optionValue == true
				if (!entry.getValue().isOptional || entry.getValue().optionValue) {
					if (entry.getValue().cachedLocation != null) {
						if (!Files.exists(Paths.get(opts.packFolder, entry.getValue().cachedLocation))) {
							invalid = true;
						}
					} else {
						// if cachedLocation == null, should probably be installed!!
						invalid = true;
					}
				}
				if (invalid) {
					URI fileUri = entry.getKey();
					System.out.println("File " + fileUri.toString() + " invalidated, marked for redownloading");
					invalidatedUris.add(fileUri);
				}
			}
		}

		if (manifest.packFileHash != null && packFileSource.hashIsEqual(manifest.packFileHash) && invalidatedUris.size() == 0) {
			System.out.println("Modpack is already up to date!");
			// todo: --force?
			return;
		}

		System.out.println("Modpack name: " + pf.name);

		try {
			// This is badly written, I'll probably heavily refactor it at some point
			processIndex(HandlerManager.getNewLoc(opts.downloadURI, pf.index.file),
					HashUtils.getHash(pf.index.hashFormat, pf.index.hash), pf.index.hashFormat, manifest, invalidatedUris);
		} catch (Exception e1) {
			ui.handleExceptionAndExit(e1);
		}

		// TODO: update MMC params, java args etc

		manifest.packFileHash = packFileSource.getHash();
		try (Writer writer = new FileWriter(Paths.get(opts.packFolder, opts.manifestFile).toString())) {
			gson.toJson(manifest, writer);
		} catch (IOException e) {
			// TODO: add message?
			ui.handleException(e);
		}

	}

	protected void checkOptions() {
		// TODO: implement
	}

	protected void processIndex(URI indexUri, Hash indexHash, String hashFormat, ManifestFile manifest, List<URI> invalidatedUris) {
		if (manifest.indexFileHash != null && manifest.indexFileHash.equals(indexHash) && invalidatedUris.size() == 0) {
			System.out.println("Modpack files are already up to date!");
			return;
		}
		manifest.indexFileHash = indexHash;

		GeneralHashingSource indexFileSource;
		try {
			Source src = HandlerManager.getFileSource(indexUri);
			indexFileSource = HashUtils.getHasher(hashFormat).getHashingSource(src);
		} catch (Exception e) {
			// TODO: still launch the game if updating doesn't work?
			// TODO: ask user if they want to launch the game, exit(1) if they don't
			ui.handleExceptionAndExit(e);
			return;
		}
		IndexFile indexFile;
		try {
			indexFile = new Toml().read(Okio.buffer(indexFileSource).inputStream()).to(IndexFile.class);
		} catch (IllegalStateException e) {
			ui.handleExceptionAndExit(e);
			return;
		}

		if (!indexFileSource.hashIsEqual(indexHash)) {
			// TODO: throw exception
			return;
		}

		if (manifest.cachedFiles == null) {
			manifest.cachedFiles = new HashMap<>();
		}

		ui.submitProgress(new InstallProgress("Checking local files..."));
		Iterator<Map.Entry<URI, ManifestFile.File>> it = manifest.cachedFiles.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<URI, ManifestFile.File> entry = it.next();
			if (entry.getValue().cachedLocation != null) {
				boolean alreadyDeleted = false;
				// Delete if option value has been set to false
				if (entry.getValue().isOptional && !entry.getValue().optionValue) {
					try {
						Files.deleteIfExists(Paths.get(opts.packFolder, entry.getValue().cachedLocation));
					} catch (IOException e) {
						// TODO: should this be shown to the user in some way?
						e.printStackTrace();
					}
					// Set to null, as it doesn't exist anymore
					entry.getValue().cachedLocation = null;
					alreadyDeleted = true;
				}
				if (indexFile.files.stream().noneMatch(f -> f.file.equals(entry.getKey()))) {
					// File has been removed from the index
					if (!alreadyDeleted) {
						try {
							Files.deleteIfExists(Paths.get(opts.packFolder, entry.getValue().cachedLocation));
						} catch (IOException e) {
							// TODO: should this be shown to the user in some way?
							e.printStackTrace();
						}
					}
					it.remove();
				}
			}
		}
		ui.submitProgress(new InstallProgress("Comparing new files..."));

		// TODO: progress bar, parallelify
		List<DownloadTask> tasks = DownloadTask.createTasksFromIndex(indexFile);
		tasks.forEach(f -> f.setDefaultHashFormat(indexFile.hashFormat));
		tasks.forEach(f -> {
			// TODO: should linkedfile be checked as well? should a getter be used?
			if (invalidatedUris.contains(f.metadata.file)) {
				f.invalidate();
			} else {
				f.updateFromCache(manifest.cachedFiles.get(f.metadata.file));
			}
		});
		tasks.forEach(f -> f.downloadMetadata(indexFile, indexUri));

		// TODO: collect all exceptions, present in one dialog
		// TODO: quit if there are exceptions or just remove failed tasks before presenting options
		List<DownloadTask> failedTasks = tasks.stream().filter(t -> t.getException() != null).collect(Collectors.toList());

		// If options changed, present all options again
		if (tasks.stream().anyMatch(DownloadTask::isNewOptional)) {
			List<IOptionDetails> opts = tasks.stream().filter(DownloadTask::isOptional).collect(Collectors.toList());
			ui.showOptions(opts);
		}

		// TODO: different thread pool type?
		ExecutorService threadPool = Executors.newFixedThreadPool(10);
		CompletionService<DownloadCompletion> completionService = new ExecutorCompletionService<>(threadPool);

		for (IndexFile.File f : newFiles) {
			ManifestFile.File cachedFile = manifest.cachedFiles.get(f.file);
			completionService.submit(() -> {
				DownloadCompletion dc = new DownloadCompletion();
				dc.file = f;

				if (cachedFile != null && cachedFile.linkedFileHash != null && f.linkedFile != null) {
					try {
						if (cachedFile.linkedFileHash.equals(f.linkedFile.getHash())) {
							// Do nothing, the file didn't change
							// TODO: but if the hash of the metafile changed, what did change?????
							// should this be checked somehow??
							return dc;
						}
					} catch (Exception ignored) {}
				}

				Path destPath = Paths.get(opts.packFolder, f.getDestURI().toString());

				// Don't update files marked with preserve if they already exist on disk
				if (f.preserve) {
					if (Files.exists(destPath)) {
						return dc;
					}
				}

				try {
					Hash hash;
					String fileHashFormat;
					if (f.linkedFile != null) {
						hash = f.linkedFile.getHash();
						fileHashFormat = f.linkedFile.download.hashFormat;
					} else {
						hash = f.getHash();
						fileHashFormat = f.hashFormat;
					}

					Source src = f.getSource(indexUri);
					GeneralHashingSource fileSource = HashUtils.getHasher(fileHashFormat).getHashingSource(src);
					Buffer data = new Buffer();
					Okio.buffer(fileSource).readAll(data);

					if (fileSource.hashIsEqual(hash)) {
						Files.createDirectories(destPath.getParent());
						Files.copy(data.inputStream(), destPath, StandardCopyOption.REPLACE_EXISTING);
					} else {
						System.out.println("Invalid hash for " + f.getDestURI().toString());
						System.out.println("Calculated: " + fileSource.getHash());
						System.out.println("Expected:   " + hash);
						dc.err = new Exception("Hash invalid!");
					}

					if (cachedFile != null && !destPath.equals(Paths.get(opts.packFolder, cachedFile.cachedLocation))) {
						// Delete old file if location changes
						Files.delete(Paths.get(opts.packFolder, cachedFile.cachedLocation));
					}

					return dc;
				} catch (Exception e) {
					dc.err = e;
					return dc;
				}
			});
		}

		for (int i = 0; i < newFiles.size(); i++) {
			DownloadCompletion ret;
			try {
				ret = completionService.take().get();
			} catch (InterruptedException | ExecutionException e) {
				// TODO: collect all exceptions, present in one dialog
				ui.handleException(e);
				ret = null;
			}
			// Update manifest
			if (ret != null && ret.err == null && ret.file != null) {
				ManifestFile.File newCachedFile = new ManifestFile.File();
				try {
					newCachedFile.hash = ret.file.getHash();
					if (newCachedFile.hash == null) {
						throw new Exception("Invalid hash!");
					}
				} catch (Exception e) {
					ret.err = e;
				}
				if (ret.file.metafile && ret.file.linkedFile != null) {
					newCachedFile.isOptional = ret.file.linkedFile.isOptional();
					if (newCachedFile.isOptional) {
						newCachedFile.optionValue = ret.file.optionValue;
					}
					try {
						newCachedFile.linkedFileHash = ret.file.linkedFile.getHash();
					} catch (Exception e) {
						ret.err = e;
					}
				}
				newCachedFile.cachedLocation = ret.file.getDestURI().toString();
				manifest.cachedFiles.put(ret.file.file, newCachedFile);
			}
			// TODO: show errors properly?
			String progress;
			if (ret != null) {
				if (ret.err != null) {
					if (ret.file != null) {
						progress = "Failed to download " + ret.file.getName() + ": " + ret.err.getMessage();
					} else {
						progress = "Failed to download: " + ret.err.getMessage();
					}
					ret.err.printStackTrace();
				} else if (ret.file != null) {
					progress = "Downloaded " + ret.file.getName();
				} else {
					progress = "Failed to download, unknown reason";
				}
			} else {
				progress = "Failed to download, unknown reason";
			}
			ui.submitProgress(new InstallProgress(progress, i + 1, newFiles.size()));
		}
		// option = false file hashes should be stored to disk, but not downloaded
		// TODO: don't include optional files in progress????
	}

	private class DownloadCompletion {
		Exception err;
		IndexFile.File file;
	}
}
