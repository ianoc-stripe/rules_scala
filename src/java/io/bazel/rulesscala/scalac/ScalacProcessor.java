package io.bazel.rulesscala.scalac;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import io.bazel.rulesscala.jar.JarCreator;
import io.bazel.rulesscala.worker.GenericWorker;
import io.bazel.rulesscala.worker.Processor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import scala.tools.nsc.*;
import scala.tools.nsc.reporters.ConsoleReporter;

class ScalacProcessor implements Processor {
  /**
   * This is the reporter field for scalac, which we want to access
   */
  private static Field reporterField;
  static {
    try {
      reporterField = Driver.class.getDeclaredField("reporter"); //NoSuchFieldException
      reporterField.setAccessible(true);
    }
    catch (NoSuchFieldException ex) {
      throw new RuntimeException("could not access reporter field on Driver", ex);
    }
  }

  private void runScalaCompiler(CompileOptions ops, String[] compilerArgs) throws Exception {
      MainClass comp = new MainClass();
      long start = System.currentTimeMillis();

      try {
        comp.process(compilerArgs);
      } catch (Throwable ex) {
        if(ex.toString().contains("scala.reflect.internal.Types$TypeError")){
          throw new RuntimeException("Build failure with type error", ex);
        } else {
          throw ex;
        }
      }

      long stop = System.currentTimeMillis();
      if (ops.printCompileTime) {
        System.err.println("Compiler runtime: " + (stop - start) + "ms.");
      }

      ConsoleReporter reporter = (ConsoleReporter) reporterField.get(comp);

      if (reporter.hasErrors()) {
          reporter.printSummary();
          reporter.flush();
          throw new RuntimeException("Build failed");
      }
  }

  public void processRequest(List<String> args) throws Exception {
    Path tmpPath = null;
    try {
      CompileOptions ops = new CompileOptions(args);

      Path outputPath = FileSystems.getDefault().getPath(ops.outputName);
      tmpPath = Files.createTempDirectory(outputPath.getParent(), "tmp");
      String[] constParams = {
        "-classpath",
        ops.classpath,
        "-d",
        tmpPath.toString()
        };

      String[] files = extractSourceJars(ops, outputPath.getParent());
      if(files.length > 0) {
         String[] compilerArgs = GenericWorker.merge(
        ops.scalaOpts,
        ops.pluginArgs,
        constParams,
        files);
         runScalaCompiler(ops, compilerArgs);
      }


        /**
         * See if there are java sources to compile
         */
        if (ops.javaFiles.length > 0) {
          compileJavaSources(ops, tmpPath);
        }
        /**
         * Copy the resources
         */
        copyResources(ops.resourceFiles, ops.resourceStripPrefix, tmpPath);
        /**
         * Now build the output jar
         */
        String[] jarCreatorArgs = {
          "-m",
          ops.manifestPath,
          outputPath.toString(),
          tmpPath.toString()
        };
        JarCreator.buildJar(jarCreatorArgs);

        /**
         * Now build the output ijar
         */
        if(ops.iJarEnabled) {
          Process iostat = new ProcessBuilder()
            .command(ops.ijarCmdPath, ops.outputName, ops.ijarOutput)
            .inheritIO()
            .start();
          int exitCode = iostat.waitFor();
          if(exitCode != 0) {
            throw new RuntimeException("ijar process failed!");
          }
        }
    }
    finally {
      removeTmp(tmpPath);
    }
  }

  static private String[] extractSourceJars(CompileOptions opts, Path tmpParent) throws IOException {
    List<File> sourceFiles = new ArrayList<File>();

    for(String jarPath : opts.sourceJars) {
      if (jarPath.length() > 0){
        Path tmpPath = Files.createTempDirectory(tmpParent, "tmp");
        sourceFiles.addAll(extractJar(jarPath, tmpPath.toString()));
      }
    }
    String[] files = GenericWorker.appendToString(opts.files, sourceFiles);
    // if(files.length == 0) {
    //   throw new RuntimeException("Must have input files from either source jars or local files.");
    // }
    return files;
  }

  private static List<File> extractJar(String jarPath,
      String outputFolder) throws IOException, FileNotFoundException {

    List<File> outputPaths = new ArrayList<File>();
    JarFile jar = new JarFile(jarPath);
    Enumeration e = jar.entries();
    while (e.hasMoreElements()) {
      JarEntry file = (JarEntry) e.nextElement();
      String thisFileName = file.getName();
      // we don't bother to extract non-scala/java sources (skip manifest)
      if (!(thisFileName.endsWith(".scala") || thisFileName.endsWith(".java"))) continue;
      File f = new File(outputFolder + File.separator + file.getName());

      if (file.isDirectory()) { // if its a directory, create it
        f.mkdirs();
        continue;
      }

      File parent = f.getParentFile();
      parent.mkdirs();
      outputPaths.add(f);

      InputStream is = jar.getInputStream(file); // get the input stream
      FileOutputStream fos = new FileOutputStream(f);
      while (is.available() > 0) {  // write contents of 'is' to 'fos'
        fos.write(is.read());
      }
      fos.close();
      is.close();
    }
    return outputPaths;
  }

  private static void compileJavaSources(CompileOptions ops, Path tmpPath) throws IOException, InterruptedException {
    ArrayList<String> commandParts = new ArrayList<>();
    commandParts.add(ops.javacPath);

    Collections.addAll(commandParts, ops.jvmFlags);
    if (!"".equals(ops.javacOpts)) {
      commandParts.add(ops.javacOpts);
    }

    commandParts.add("-classpath");
    commandParts.add(ops.classpath + ":" + tmpPath.toString());
    commandParts.add("-d");
    commandParts.add(tmpPath.toString());
    for(String javaFile : ops.javaFiles) {
      commandParts.add(javaFile.toString());
    }

    Process iostat = new ProcessBuilder(commandParts)
      .inheritIO()
      .start();
    int exitCode = iostat.waitFor();
    if(exitCode != 0) {
      throw new RuntimeException("javac process failed!");
    }
  }
  private static void removeTmp(Path tmp) throws IOException {
    if (tmp != null) {
      Files.walkFileTree(tmp, new SimpleFileVisitor<Path>() {
         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
             Files.delete(file);
             return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
             Files.delete(dir);
             return FileVisitResult.CONTINUE;
         }
      });
    }
  }
  private static void copyResources(
      Map<String, String> resources,
      String resourceStripPrefix,
      Path dest) throws IOException {
    for(Entry<String, String> e : resources.entrySet()) {
      Path source = Paths.get(e.getKey());
      String dstr;
      // Check if we need to modify resource destination path
      if (!"".equals(resourceStripPrefix)) {
  /**
   * NOTE: We are not using the Resource Hash Value as the destination path
   * when `resource_strip_prefix` present. The path in the hash value is computed
   * by the `_adjust_resources_path` in `scala.bzl`. These are the default paths,
   * ie, path that are automatically computed when there is no `resource_strip_prefix`
   * present. But when `resource_strip_prefix` is present, we need to strip the prefix
   * from the Source Path and use that as the new destination path
   * Refer Bazel -> BazelJavaRuleClasses.java#L227 for details
   */
        dstr = getResourcePath(source, resourceStripPrefix);
      } else {
        dstr = e.getValue();
      }
      if (dstr.charAt(0) == '/') dstr = dstr.substring(1);
      Path target = dest.resolve(dstr);
      File tfile = target.getParent().toFile();
      tfile.mkdirs();
      Files.copy(source, target);
    }
  }
  private static String getResourcePath(
      Path source,
      String resourceStripPrefix) throws RuntimeException {
    String sourcePath = source.toString();
    // check if the Resource file is under the specified prefix to strip
    if (!sourcePath.startsWith(resourceStripPrefix)) {
      // Resource File is not under the specified prefix to strip
      throw new RuntimeException("Resource File "
        + sourcePath
        + " is not under the specified strip prefix "
        + resourceStripPrefix);
    }
    String newResPath = sourcePath.substring(resourceStripPrefix.length());
    return newResPath;
  }
}
