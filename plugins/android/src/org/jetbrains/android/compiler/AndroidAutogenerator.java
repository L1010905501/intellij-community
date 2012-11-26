package org.jetbrains.android.compiler;


import com.android.SdkConstants;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.internal.build.BuildConfigGenerator;
import com.intellij.compiler.impl.CompilerUtil;
import com.intellij.compiler.impl.ModuleCompileScope;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import org.jetbrains.android.compiler.tools.AndroidIdl;
import org.jetbrains.android.compiler.tools.AndroidRenderscript;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.AndroidRootUtil;
import org.jetbrains.android.fileTypes.AndroidIdlFileType;
import org.jetbrains.android.fileTypes.AndroidRenderscriptFileType;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * @author Eugene.Kudelevsky
 */
public class AndroidAutogenerator {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.android.compiler.AndroidAutogenerator");

  private AndroidAutogenerator() {
  }

  private static boolean toRun(@NotNull AndroidAutogeneratorMode mode, @NotNull AndroidFacet facet) {
    switch (mode) {
      case AAPT:
        return AndroidAptCompiler.isToCompileModule(facet.getModule(), facet.getConfiguration());
      case AIDL:
      case RENDERSCRIPT:
      case BUILDCONFIG:
        return true;
      default:
        LOG.error("Unknown autogenerator mode " + mode);
        return false;
    }
  }

  public static void run(@NotNull AndroidAutogeneratorMode mode, @NotNull AndroidFacet facet, @NotNull CompileContext context) {
    if (!toRun(mode, facet)) {
      return;
    }
    final Set<String> obsoleteFiles = new HashSet<String>(facet.getAutogeneratedFiles(mode));

    switch (mode) {
      case AAPT:
        runAapt(facet, context);
        break;
      case AIDL:
        runAidl(facet, context);
        break;
      case RENDERSCRIPT:
        runRenderscript(facet, context);
        break;
      case BUILDCONFIG:
        runBuildConfigGenerator(facet, context);
        break;
      default:
        LOG.error("Unknown mode" + mode);
    }
    obsoleteFiles.removeAll(facet.getAutogeneratedFiles(mode));

    for (String path : obsoleteFiles) {
      final File file = new File(path);

      if (file.isFile()) {
        FileUtil.delete(file);
        CompilerUtil.refreshIOFile(file);
      }
    }
  }

  private static void runBuildConfigGenerator(@NotNull final AndroidFacet facet, @NotNull final CompileContext context) {
    final Module module = facet.getModule();

    final BuildconfigAutogenerationItem item = ApplicationManager.getApplication().runReadAction(
      new Computable<BuildconfigAutogenerationItem>() {
        @Nullable
        @Override
        public BuildconfigAutogenerationItem compute() {
          if (module.isDisposed() || module.getProject().isDisposed()) {
            return null;
          }

          final String sourceRootPath = AndroidRootUtil.getBuildconfigGenSourceRootPath(facet);
          if (sourceRootPath == null) {
            return null;
          }

          final VirtualFile manifestFile = AndroidRootUtil.getManifestFileForCompiler(facet);
          if (manifestFile == null) {
            context.addMessage(CompilerMessageCategory.ERROR,
                               AndroidBundle.message("android.compilation.error.manifest.not.found", module.getName()), null, -1, -1);
            return null;
          }

          final Manifest manifest = AndroidUtils.loadDomElement(module, manifestFile, Manifest.class);
          if (manifest == null) {
            context.addMessage(CompilerMessageCategory.ERROR, "Cannot parse file", manifestFile.getUrl(), -1, -1);
            return null;
          }

          String packageName = manifest.getPackage().getValue();
          if (packageName != null) {
            packageName = packageName.trim();
          }

          if (packageName == null || packageName.length() <= 0) {
            context.addMessage(CompilerMessageCategory.ERROR, AndroidBundle.message("package.not.found.error"), manifestFile.getUrl(),
                               -1, -1);
            return null;
          }
          return new BuildconfigAutogenerationItem(packageName, FileUtil.toSystemDependentName(sourceRootPath));
        }
      });

    if (item == null) {
      return;
    }

    // doesn't matter, because autogenerated class is used for resolve/completion only
    final boolean debug = true;
    final BuildConfigGenerator generator = new BuildConfigGenerator(item.mySourceRootOsPath, item.myPackage, debug);
    try {
      generator.generate();

      final VirtualFile genSourceRoot = LocalFileSystem.getInstance().findFileByPath(item.mySourceRootOsPath);
      if (genSourceRoot != null) {
        genSourceRoot.refresh(false, true);
      }
      facet.clearAutogeneratedFiles(AndroidAutogeneratorMode.BUILDCONFIG);

      final VirtualFile genFile = LocalFileSystem.getInstance().findFileByPath(
        item.mySourceRootOsPath + '/' + item.myPackage.replace('.', '/') + '/' + BuildConfigGenerator.BUILD_CONFIG_NAME);

      if (genFile != null && genFile.exists()) {
        facet.markFileAutogenerated(AndroidAutogeneratorMode.BUILDCONFIG, genFile);
      }
    }
    catch (final IOException e) {
      LOG.info(e);
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          if (module.getProject().isDisposed()) return;
          context.addMessage(CompilerMessageCategory.ERROR, "I/O error: " + e.getMessage(), null, -1, -1);
        }
      });
    }
  }

  private static void runAapt(@NotNull final AndroidFacet facet, @NotNull final CompileContext context) {
    final Module module = facet.getModule();

    final AptAutogenerationItem item = ApplicationManager.getApplication().runReadAction(new Computable<AptAutogenerationItem>() {
      @Nullable
      @Override
      public AptAutogenerationItem compute() {
        if (module.isDisposed() || module.getProject().isDisposed()) {
          return null;
        }

        final VirtualFile manifestFile = AndroidRootUtil.getManifestFileForCompiler(facet);
        if (manifestFile == null) {
          context.addMessage(CompilerMessageCategory.ERROR,
                             AndroidBundle.message("android.compilation.error.manifest.not.found", module.getName()), null, -1, -1);
          return null;
        }

        final Manifest manifest = AndroidUtils.loadDomElement(module, manifestFile, Manifest.class);
        if (manifest == null) {
          context.addMessage(CompilerMessageCategory.ERROR, "Cannot parse file", manifestFile.getUrl(), -1, -1);
          return null;
        }

        String packageName = manifest.getPackage().getValue();
        if (packageName != null) {
          packageName = packageName.trim();
        }

        if (packageName == null || packageName.length() <= 0) {
          context.addMessage(CompilerMessageCategory.ERROR, AndroidBundle.message("package.not.found.error"), manifestFile.getUrl(),
                             -1, -1);
          return null;
        }

        final String sourceRootPath = AndroidRootUtil.getAptGenSourceRootPath(facet);
        if (sourceRootPath == null) {
          context.addMessage(CompilerMessageCategory.ERROR,
                             AndroidBundle.message("android.compilation.error.apt.gen.not.specified", module.getName()),
                             null, -1, -1);
          return null;
        }

        final Map<String, String> genFilePath2Package = new HashMap<String, String>();

        final String packageDir = packageName.replace('.', '/') + '/';
        genFilePath2Package.put(packageDir + AndroidCommonUtils.MANIFEST_JAVA_FILE_NAME, packageName);
        genFilePath2Package.put(packageDir + AndroidCommonUtils.R_JAVA_FILENAME, packageName);
        return new AptAutogenerationItem(packageName, sourceRootPath, genFilePath2Package);
      }
    });

    if (item == null) {
      return;
    }

    final Set<VirtualFile> filesToCheck = new HashSet<VirtualFile>();

    for (String genFileRelPath : item.myGenFileRelPath2package.keySet()) {
      final String genFileFullPath = item.myOutputDirOsPath + '/' + genFileRelPath;

      if (new File(genFileFullPath).exists()) {
        final VirtualFile genFile = LocalFileSystem.getInstance().findFileByPath(genFileFullPath);

        if (genFile != null) {
          filesToCheck.add(genFile);
        }
      }
    }

    if (!ensureFilesWritable(module.getProject(), filesToCheck)) {
      return;
    }

    File tempOutDir = null;

    try {
      // Aapt generation can be very long, so we generate it in temp directory first
      tempOutDir = FileUtil.createTempDirectory("android_apt_autogeneration", "tmp");

      generateStubClasses(item.myPackage, tempOutDir,
                          AndroidUtils.R_CLASS_NAME,
                          AndroidUtils.MANIFEST_CLASS_NAME);

      for (String genFileRelPath : item.myGenFileRelPath2package.keySet()) {
        final File srcFile = new File(tempOutDir.getPath() + '/' + genFileRelPath);

        if (srcFile.isFile()) {
          final File dstFile = new File(item.myOutputDirOsPath + '/' + genFileRelPath);

          if (dstFile.exists()) {
            if (!FileUtil.delete(dstFile)) {
              ApplicationManager.getApplication().runReadAction(new Runnable() {
                @Override
                public void run() {
                  if (module.isDisposed() || module.getProject().isDisposed()) {
                    return;
                  }
                  context.addMessage(CompilerMessageCategory.ERROR,
                                     "Cannot delete " + FileUtil.toSystemDependentName(dstFile.getPath()), null, -1, -1);
                }
              });
            }
          }
          FileUtil.rename(srcFile, dstFile);
        }
      }

      for (Map.Entry<String, String> entry : item.myGenFileRelPath2package.entrySet()) {
        final String path = item.myOutputDirOsPath + '/' + entry.getKey();
        final String aPackage = entry.getValue();
        final File file = new File(path);
        CompilerUtil.refreshIOFile(file);

        removeAllFilesWithSameName(module, file, item.myOutputDirOsPath);
        removeDuplicateClasses(module, aPackage, file, item.myOutputDirOsPath);
      }

      final VirtualFile genSourceRoot = LocalFileSystem.getInstance().findFileByPath(item.myOutputDirOsPath);
      if (genSourceRoot != null) {
        genSourceRoot.refresh(false, true);
      }
      facet.clearAutogeneratedFiles(AndroidAutogeneratorMode.AAPT);

      for (String relPath : item.myGenFileRelPath2package.keySet()) {
        final VirtualFile genFile = LocalFileSystem.getInstance().findFileByPath(item.myOutputDirOsPath + '/' + relPath);

        if (genFile != null && genFile.exists()) {
          facet.markFileAutogenerated(AndroidAutogeneratorMode.AAPT, genFile);
        }
      }
    }
    catch (final IOException e) {
      LOG.info(e);
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          if (module.getProject().isDisposed()) return;
          context.addMessage(CompilerMessageCategory.ERROR, "I/O error: " + e.getMessage(), null, -1, -1);
        }
      });
    }
    finally {
      if (tempOutDir != null) {
        FileUtil.delete(tempOutDir);
      }
    }
  }

  private static void generateStubClasses(@NotNull String aPackage, @NotNull File outputDir, @NotNull String... classNames)
    throws IOException {
    assert aPackage.length() > 0;

    for (String className : classNames) {
      final File packageDir = new File(outputDir.getPath() + '/' + aPackage.replace('.', '/'));
      if (!packageDir.exists() && !packageDir.mkdirs()) {
        throw new IOException("Cannot create directory " + FileUtil.toSystemDependentName(packageDir.getPath()));
      }
      final BufferedWriter writer = new BufferedWriter(new FileWriter(new File(packageDir, className + ".java")));
      try {
        writer.write(
          "package " + aPackage + ";\n\n" +
          "/* This stub is for using by IDE only. It is NOT the " + className + " class actually packed into APK */\n" +
          "public final class " + className + " {\n" +
          "}"
        );
      }
      finally {
        writer.close();
      }
    }
  }

  private static void removeAllFilesWithSameName(@NotNull final Module module, @NotNull final File file, @NotNull String directoryPath) {
    final VirtualFile vFile = LocalFileSystem.getInstance().findFileByIoFile(file);
    final VirtualFile genDir = LocalFileSystem.getInstance().findFileByPath(directoryPath);

    if (vFile == null || genDir == null) {
      return;
    }
    DumbService.getInstance(module.getProject()).waitForSmartMode();

    final Collection<VirtualFile> files = ApplicationManager.getApplication().runReadAction(new Computable<Collection<VirtualFile>>() {
      @Nullable
      @Override
      public Collection<VirtualFile> compute() {
        if (module.isDisposed() || module.getProject().isDisposed()) {
          return null;
        }
        return FilenameIndex.getVirtualFilesByName(module.getProject(), file.getName(), module.getModuleScope(false));
      }
    });
    if (files == null) {
      return;
    }

    final List<VirtualFile> filesToDelete = new ArrayList<VirtualFile>();

    for (final VirtualFile f : files) {
      if (!Comparing.equal(f, vFile) && VfsUtilCore.isAncestor(genDir, f, true)) {
        filesToDelete.add(f);
      }
    }
    if (filesToDelete.size() == 0) {
      return;
    }

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            for (VirtualFile f : filesToDelete) {
              if (f.isValid() && f.exists()) {
                try {
                  f.delete(module.getProject());
                }
                catch (IOException e) {
                  LOG.debug(e);
                }
              }
            }
          }
        });
      }
    });
  }

  private static void runAidl(@NotNull final AndroidFacet facet, @NotNull final CompileContext context) {
    final Module module = facet.getModule();
    final ModuleCompileScope moduleCompileScope = new ModuleCompileScope(module, false);
    final VirtualFile[] files = moduleCompileScope.getFiles(AndroidIdlFileType.ourFileType, true);
    final List<IdlAutogenerationItem> items = new ArrayList<IdlAutogenerationItem>();

    for (final VirtualFile file : files) {
      final IdlAutogenerationItem item = ApplicationManager.getApplication().runReadAction(new Computable<IdlAutogenerationItem>() {
        @Nullable
        @Override
        public IdlAutogenerationItem compute() {
          if (module.isDisposed() || module.getProject().isDisposed()) {
            return null;
          }

          final IAndroidTarget target = facet.getConfiguration().getAndroidTarget();
          if (target == null) {
            context.addMessage(CompilerMessageCategory.ERROR,
                               AndroidBundle.message("android.compilation.error.specify.platform", module.getName()), null, -1, -1);
            return null;
          }

          final String packageName = AndroidUtils.computePackageName(module, file);
          if (packageName == null) {
            context.addMessage(CompilerMessageCategory.ERROR, "Cannot compute package for file", file.getUrl(), -1, -1);
            return null;
          }

          final String sourceRootPath = AndroidRootUtil.getAidlGenSourceRootPath(facet);
          if (sourceRootPath == null) {
            context.addMessage(CompilerMessageCategory.ERROR,
                               AndroidBundle.message("android.compilation.error.apt.gen.not.specified", module.getName()), null, -1, -1);
            return null;
          }

          final VirtualFile[] sourceRoots = AndroidPackagingCompiler.getSourceRootsForModuleAndDependencies(module, false);
          final String[] sourceRootOsPaths = AndroidCompileUtil.toOsPaths(sourceRoots);

          final String outFileOsPath = FileUtil.toSystemDependentName(
            sourceRootPath + '/' + packageName.replace('.', '/') + '/' + file.getNameWithoutExtension() + ".java");

          return new IdlAutogenerationItem(file, target, outFileOsPath, sourceRootOsPaths, sourceRootPath, packageName);
        }
      });

      if (item != null) {
        items.add(item);
      }
    }

    final Set<VirtualFile> filesToCheck = new HashSet<VirtualFile>();

    for (IdlAutogenerationItem item : items) {
      if (new File(FileUtil.toSystemDependentName(item.myFile.getPath())).exists()) {
        filesToCheck.add(item.myFile);
      }
    }

    if (!ensureFilesWritable(module.getProject(), filesToCheck)) {
      return;
    }

    facet.clearAutogeneratedFiles(AndroidAutogeneratorMode.AIDL);

    for (IdlAutogenerationItem item : items) {
      final VirtualFile file = item.myFile;
      final String fileOsPath = FileUtil.toSystemDependentName(file.getPath());

      try {
        final Map<CompilerMessageCategory, List<String>> messages = AndroidCompileUtil.toCompilerMessageCategoryKeys(
          AndroidIdl.execute(item.myTarget, fileOsPath, item.myOutFileOsPath, item.mySourceRootOsPaths));

        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            if (module.getProject().isDisposed()) return;

            for (CompilerMessageCategory category : messages.keySet()) {
              List<String> messageList = messages.get(category);
              for (String message : messageList) {
                context.addMessage(category, message, file.getUrl(), -1, -1);
              }
            }
          }
        });

        removeDuplicateClasses(module, item.myPackage, new File(item.myOutFileOsPath), item.myOutDirOsPath);

        final VirtualFile genDir = LocalFileSystem.getInstance().findFileByPath(item.myOutDirOsPath);
        if (genDir != null) {
          genDir.refresh(false, true);
        }

        final VirtualFile outFile = LocalFileSystem.getInstance().findFileByPath(item.myOutFileOsPath);
        if (outFile != null && outFile.exists()) {
          facet.markFileAutogenerated(AndroidAutogeneratorMode.AIDL, outFile);
        }
      }
      catch (final IOException e) {
        LOG.info(e);
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            if (module.getProject().isDisposed()) return;
            context.addMessage(CompilerMessageCategory.ERROR, e.getMessage(), file.getUrl(), -1, -1);
          }
        });
      }
    }
  }

  private static void runRenderscript(@NotNull final AndroidFacet facet, @NotNull final CompileContext context) {
    final Module module = facet.getModule();

    final ModuleCompileScope moduleCompileScope = new ModuleCompileScope(module, false);
    final VirtualFile[] files = moduleCompileScope.getFiles(AndroidRenderscriptFileType.INSTANCE, true);

    facet.clearAutogeneratedFiles(AndroidAutogeneratorMode.RENDERSCRIPT);

    for (final VirtualFile file : files) {
      final RenderscriptAutogenerationItem item =
        ApplicationManager.getApplication().runReadAction(new Computable<RenderscriptAutogenerationItem>() {
          @Nullable
          @Override
          public RenderscriptAutogenerationItem compute() {
            final AndroidPlatform platform = facet.getConfiguration().getAndroidPlatform();
            if (platform == null) {
              context.addMessage(CompilerMessageCategory.ERROR,
                                 AndroidBundle.message("android.compilation.error.specify.platform", module.getName()), null, -1, -1);
              return null;
            }

            final IAndroidTarget target = platform.getTarget();
            final String sdkLocation = platform.getSdkData().getLocation();

            final String packageName = AndroidUtils.computePackageName(module, file);
            if (packageName == null) {
              context.addMessage(CompilerMessageCategory.ERROR, "Cannot compute package for file", file.getUrl(), -1, -1);
              return null;
            }

            final String resourceDirPath = AndroidRootUtil.getResourceDirPath(facet);
            assert resourceDirPath != null;

            final String sourceRootPath = AndroidRootUtil.getRenderscriptGenSourceRootPath(facet);
            if (sourceRootPath == null) {
              return null;
            }

            final String rawDirPath = resourceDirPath + '/' + SdkConstants.FD_RES_RAW;

            return new RenderscriptAutogenerationItem(sdkLocation, target, sourceRootPath, rawDirPath);
          }
        });

      if (item == null) {
        continue;
      }

      File tempOutDir = null;

      try {
        tempOutDir = FileUtil.createTempDirectory("android_renderscript_autogeneration", "tmp");
        final VirtualFile vTempOutDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempOutDir);

        final String depFolderPath =
          vTempOutDir != null ? AndroidRenderscriptCompiler.getDependencyFolder(context.getProject(), file, vTempOutDir) : null;

        final Map<CompilerMessageCategory, List<String>> messages = AndroidCompileUtil.toCompilerMessageCategoryKeys(
          AndroidRenderscript
            .execute(item.mySdkLocation, item.myTarget, file.getPath(), tempOutDir.getPath(), depFolderPath,
                     item.myRawDirPath));

        if (messages.get(CompilerMessageCategory.ERROR).size() == 0) {
          final List<File> newFiles = new ArrayList<File>();
          AndroidCommonUtils.moveAllFiles(tempOutDir, new File(item.myGenDirPath), newFiles);

          for (File newFile : newFiles) {
            final VirtualFile newVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(newFile);

            if (newVFile != null) {
              facet.markFileAutogenerated(AndroidAutogeneratorMode.RENDERSCRIPT, newVFile);
            }
          }

          final File bcFile = new File(item.myRawDirPath, FileUtil.getNameWithoutExtension(file.getName()) + ".bc");
          final VirtualFile vBcFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(bcFile);

          if (vBcFile != null) {
            facet.markFileAutogenerated(AndroidAutogeneratorMode.RENDERSCRIPT, vBcFile);
          }
        }

        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            if (module.getProject().isDisposed()) {
              return;
            }

            for (final CompilerMessageCategory category : messages.keySet()) {
              final List<String> messageList = messages.get(category);
              for (final String message : messageList) {
                context.addMessage(category, message, file.getUrl(), -1, -1);
              }
            }
          }
        });

        final VirtualFile genDir = LocalFileSystem.getInstance().findFileByPath(item.myGenDirPath);
        if (genDir != null) {
          genDir.refresh(false, true);
        }
      }
      catch (final IOException e) {
        LOG.info(e);
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            if (module.getProject().isDisposed()) return;
            context.addMessage(CompilerMessageCategory.ERROR, e.getMessage(), file.getUrl(), -1, -1);
          }
        });
      }
      finally {
        if (tempOutDir != null) {
          FileUtil.delete(tempOutDir);
        }
      }
    }
  }

  private static boolean ensureFilesWritable(@NotNull final Project project, @NotNull final Collection<VirtualFile> filesToCheck) {
    if (filesToCheck.size() == 0) {
      return true;
    }
    final boolean[] run = {false};

    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            run[0] = !project.isDisposed() &&
                     ReadonlyStatusHandler.ensureFilesWritable(project, filesToCheck.toArray(new VirtualFile[filesToCheck.size()]));
          }
        });
      }
    }, ModalityState.defaultModalityState());

    return run[0];
  }

  private static void removeDuplicateClasses(@NotNull final Module module,
                                             @NotNull final String aPackage,
                                             @NotNull final File generatedFile,
                                             @NotNull final String sourceRootPath) {
    if (generatedFile.exists()) {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          if (module.getProject().isDisposed() || module.isDisposed()) {
            return;
          }
          String className = FileUtil.getNameWithoutExtension(generatedFile);
          AndroidCompileUtil.removeDuplicatingClasses(module, aPackage, className, generatedFile, sourceRootPath);
        }
      });
    }
  }

  private static class AptAutogenerationItem {
    final String myPackage;
    final String myOutputDirOsPath;
    final Map<String, String> myGenFileRelPath2package;

    private AptAutogenerationItem(@NotNull String aPackage,
                                  @NotNull String outputDirOsPath,
                                  @NotNull Map<String, String> genFileRelPath2package) {
      myPackage = aPackage;
      myOutputDirOsPath = outputDirOsPath;
      myGenFileRelPath2package = genFileRelPath2package;
    }
  }

  private static class IdlAutogenerationItem {
    final VirtualFile myFile;
    final IAndroidTarget myTarget;
    final String myOutFileOsPath;
    final String[] mySourceRootOsPaths;
    final String myOutDirOsPath;
    final String myPackage;

    private IdlAutogenerationItem(@NotNull VirtualFile file,
                                  @NotNull IAndroidTarget target,
                                  @NotNull String outFileOsPath,
                                  @NotNull String[] sourceRootOsPaths,
                                  @NotNull String outDirOsPath,
                                  @NotNull String aPackage) {
      myFile = file;
      myTarget = target;
      myOutFileOsPath = outFileOsPath;
      mySourceRootOsPaths = sourceRootOsPaths;
      myOutDirOsPath = outDirOsPath;
      myPackage = aPackage;
    }
  }

  private static class RenderscriptAutogenerationItem {
    final String mySdkLocation;
    final IAndroidTarget myTarget;
    final String myGenDirPath;
    final String myRawDirPath;

    private RenderscriptAutogenerationItem(@NotNull String sdkLocation,
                                           @NotNull IAndroidTarget target,
                                           @NotNull String genDirPath,
                                           @NotNull String rawDirPath) {
      mySdkLocation = sdkLocation;
      myTarget = target;
      myGenDirPath = genDirPath;
      myRawDirPath = rawDirPath;
    }
  }

  private static class BuildconfigAutogenerationItem {
    final String myPackage;
    final String mySourceRootOsPath;

    private BuildconfigAutogenerationItem(@NotNull String aPackage, @NotNull String sourceRootOsPath) {
      myPackage = aPackage;
      mySourceRootOsPath = sourceRootOsPath;
    }
  }
}
