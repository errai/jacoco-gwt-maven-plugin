/*******************************************************************************
 * Copyright (c) 2009, 2011 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Evgeny Mandrikov - initial API and implementation
 *    Jonathan Fuerth - additional class snapshot agent and GWT support
 *
 *******************************************************************************/
package org.jboss.errai.gwtmaven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICoverageNode;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportGroupVisitor;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.ISourceFileLocator;
import org.jacoco.report.MultiReportVisitor;
import org.jacoco.report.csv.CSVFormatter;
import org.jacoco.report.html.HTMLFormatter;
import org.jacoco.report.xml.XMLFormatter;

/**
 * Creates a code coverage report for a single project in multiple formats
 * (HTML, XML, and CSV).
 * 
 * @goal report
 * @requiresProject true
 */
public class ReportMojo extends AbstractJacocoMojo {

  /**
   * Output directory for the reports.
   * 
   * @parameter default-value="${project.reporting.outputDirectory}/jacoco"
   */
  private File outputDirectory;

  /**
   * Encoding of the generated reports.
   * 
   * @parameter expression="${project.reporting.outputEncoding}"
   *            default-value="UTF-8"
   */
  private String outputEncoding;

  /**
   * Encoding of the source files.
   * 
   * @parameter expression="${project.build.sourceEncoding}"
   *            default-value="UTF-8"
   */
  private String sourceEncoding;

  /**
   * File with execution data.
   * 
   * @parameter default-value="${project.build.directory}/jacoco.exec"
   */
  private File dataFile;

  /**
   * Base directory where runtime class snapshots were saved by the snapshot
   * agent.
   * 
   * @parameter default-value="${project.build.directory}/snapshot-classes"
   */
  private File snapshotDirectory;

  /**
   * The classes which are exercised from GWTTestCase and the unit tests,
   * but shouldn't taken from the GWTTestCase recording to calculate the coverage.
   * This is a comma separated list of regex patterns.
   * Be aware that you have to match the compiled classes (eg. DoSomething.class, DoSomething$Innerclass.class).
   *
   * @parameter default-value=""
   */
  private String unitTestPrecedences;

  private SessionInfoStore sessionInfoStore;

  private ExecutionDataStore executionDataStore;

  @Override
  protected void executeMojo() {
    try {
      loadExecutionData();
    } catch (final IOException e) {
      getLog().error(
          "Unable to read execution data file " + dataFile + ": " + e.getMessage(), e);
      return;
    }
    try {
      final IReportVisitor visitor = createVisitor();
      visitor.visitInfo(sessionInfoStore.getInfos(),
          executionDataStore.getContents());
      createReport(visitor);
      visitor.visitEnd();
    } catch (final Exception e) {
      getLog().error("Error while creating report: " + e.getMessage(), e);
    }
  }

  private void loadExecutionData() throws IOException {
    sessionInfoStore = new SessionInfoStore();
    executionDataStore = new ExecutionDataStore();
    FileInputStream in = null;
    try {
      in = new FileInputStream(dataFile);
      final ExecutionDataReader reader = new ExecutionDataReader(in);
      reader.setSessionInfoVisitor(sessionInfoStore);
      reader.setExecutionDataVisitor(executionDataStore);
      reader.read();
    } finally {
      if (in != null) {
        in.close();
      }
    }
  }

  private void createReport(final IReportGroupVisitor visitor)
      throws IOException {
    final IBundleCoverage bundle = createBundle();
    final SourceFileCollection locator = new SourceFileCollection(getCompileSourceRoots(), sourceEncoding);
    checkForMissingDebugInformation(bundle);
    visitor.visitBundle(bundle, locator);
  }

  private void checkForMissingDebugInformation(final ICoverageNode node) {
    if (node.getClassCounter().getTotalCount() > 0
        && node.getLineCounter().getTotalCount() == 0) {
      getLog().warn(
              "To enable source code annotation, class files have to be compiled with debug information.");
    }
  }

  private IBundleCoverage createBundle() throws IOException {
    final CoverageBuilder builder = new CoverageBuilder();
    final Analyzer analyzer = new Analyzer(executionDataStore, builder);
    final File classesDir = new File(getProject().getBuild().getOutputDirectory());

    List<File> filesToAnalyze = getFilesToAnalyze(classesDir);

    for (File file : filesToAnalyze) {
      analyzer.analyzeAll(file);
    }

    return builder.getBundle(getProject().getName());
  }

  private IReportVisitor createVisitor() throws IOException {
    final List<IReportVisitor> visitors = new ArrayList<IReportVisitor>();

    outputDirectory.mkdirs();

    final XMLFormatter xmlFormatter = new XMLFormatter();
    xmlFormatter.setOutputEncoding(outputEncoding);
    visitors.add(xmlFormatter.createVisitor(new FileOutputStream(new File(outputDirectory, "jacoco.xml"))));

    final CSVFormatter formatter = new CSVFormatter();
    formatter.setOutputEncoding(outputEncoding);
    visitors.add(formatter.createVisitor(new FileOutputStream(new File(outputDirectory, "jacoco.csv"))));

    final HTMLFormatter htmlFormatter = new HTMLFormatter();
    // formatter.setFooterText(footer);
    htmlFormatter.setOutputEncoding(outputEncoding);
    // formatter.setLocale(locale);
    visitors.add(htmlFormatter.createVisitor(new FileMultiReportOutput(outputDirectory)));

    return new MultiReportVisitor(visitors);
  }

  private static class SourceFileCollection implements ISourceFileLocator {

    private final List<File> sourceRoots;
    private final String encoding;

    public SourceFileCollection(final List<File> sourceRoots, final String encoding) {
      this.sourceRoots = sourceRoots;
      this.encoding = encoding;
    }

    public Reader getSourceFile(final String packageName, final String fileName)
        throws IOException {
      final String r;
      if (packageName.length() > 0) {
        r = packageName + '/' + fileName;
      }
      else {
        r = fileName;
      }
      for (final File sourceRoot : sourceRoots) {
        final File file = new File(sourceRoot, r);
        if (file.exists() && file.isFile()) {
          return new InputStreamReader(new FileInputStream(file), encoding);
        }
      }
      return null;
    }

    public int getTabWidth() {
      return 4;
    }
  }

  private File resolvePath(final String path) {
    File file = new File(path);
    if (!file.isAbsolute()) {
      file = new File(getProject().getBasedir(), path);
    }
    return file;
  }

  private List<File> getCompileSourceRoots() {
    final List<File> result = new ArrayList<File>();
    for (final Object path : getProject().getCompileSourceRoots()) {
      result.add(resolvePath((String) path));
    }
    return result;
  }

  /**
   * Returns the list of compiled classes from the current project, shadowed by
   * the classes found under {@link #snapshotDirectory}.
   * <p>
   * <h2>About shadowing</h2> For the purposes of this class, shadowing is
   * defined as follows: if set C is made up of the set A <i>shadowed by</i> the
   * set B, then C contains all items from A that do not have equivalents in B,
   * plus all items in B that have equivalents in A. Items in B that have no
   * equivalents in A do not appear in C.
   * <p>
   * For example, consider the following directory structure, where
   * {@code rootDir} is "target/classes" and {@code snapshotDirectory} is
   * "target/snapshot-classes":
   * 
   * <pre>
   *  target/classes/com/foo/Foo.class
   *  target/classes/com/foo/Bar.class
   *  target/snapshot-classes/com/foo/Bar.class
   *  target/snapshot-classes/com/foo/Baz.class
   * </pre>
   * 
   * the returned set would look like this:
   * 
   * <pre>
   *  target/classes/com/foo/Foo.class
   *  target/snapshot-classes/com/foo/Bar.class
   * </pre>
   * 
   * Notice the output contains classes com.foo.Foo and com.foo.Bar. The output
   * does not contain a com.foo.Baz because com.foo.Baz wasn't present under
   * target/classes. The output uses the snapshot for com.foo.Bar because that
   * class was present in both locations.
   * 
   * @param rootDir
   *          The directory to look in for classes
   * @return Absolute paths to the files under rootDir <i>shadowed by</i> the
   *         equivalent files under {@link #snapshotDirectory} (if any).
   */
  protected List<File> getFilesToAnalyze(File rootDir) throws IOException {
    final String includes;
    if (getIncludes() != null && !getIncludes().isEmpty()) {
      includes = StringUtils.join(getIncludes().iterator(), ",");
    }
    else {
      includes = "**";
    }
    final String excludes;
    if (getExcludes() != null && !getExcludes().isEmpty()) {
      excludes = StringUtils.join(getExcludes().iterator(), ",");
    }
    else {
      excludes = "";
    }

    // build two collections of relative pathnames
    List<?> originalClasses = FileUtils.getFiles(rootDir, includes, excludes, false);
    Set<File> snapshotClasses = getSnapshotRelativeFiles();

    // now build one list of absolute pathnames, using items from the snapshot
    // list where they exist
    List<File> filesToAnalyze = new ArrayList<File>(originalClasses.size());

    // create the regex for the classes which should taken from the originalClasses
    Set<Pattern> unitTestPrecedencePatterns = new HashSet<Pattern>();
    if(StringUtils.isNotEmpty(unitTestPrecedences)) {
      String[] unitTestPrecedences = this.unitTestPrecedences.split(",");
      for(String unitTestPrecedence: unitTestPrecedences) {
        getLog().info("added unitTestPrecedence pattern: " + unitTestPrecedence);
        unitTestPrecedencePatterns.add(Pattern.compile(unitTestPrecedence));
      }
    }

    for (Object o : originalClasses) {
      File f = (File) o;
      if (snapshotContainClassAndPrecedenceIsNotOnUnitTest(snapshotClasses, f, unitTestPrecedencePatterns)) {
        filesToAnalyze.add(new File(snapshotDirectory, f.getPath()));
        getLog().debug("Using snapshot class for " + f);
      }
      else {
        filesToAnalyze.add(new File(rootDir, f.getPath()));
      }
    }

    return filesToAnalyze;
  }

  private boolean snapshotContainClassAndPrecedenceIsNotOnUnitTest(Set<File> snapshotClasses, File f, Set<Pattern> unitTestPrecedencePatterns) {
    if(!unitTestPrecedencePatterns.isEmpty()){
      for(Pattern unitTestPrecedencePattern: unitTestPrecedencePatterns) {
        if(unitTestPrecedencePattern.matcher(f.getName()).matches()) {
          getLog().info("for class: " + f.getName() + " we give the unit test precedence [pattern: " + unitTestPrecedencePattern + "]");
          return false;
        }
      }
    }
    if(snapshotClasses.contains(f)) {
      return true;
    }
    return false;
  }

  /**
   * Returns a set of File objects denoting the relative paths of all the files
   * under {@link #snapshotDirectory}. To turn an item {@code f} from the
   * returned set into an absolute file, use the statement
   * {@code new File(snapshotDirectory, f.getPath())}.
   * 
   * @return a set of relative paths for all files under {@link #snapshotDirectory}.
   */
  @SuppressWarnings("unchecked")
  private HashSet<File> getSnapshotRelativeFiles() throws IOException {
    if(!snapshotDirectory.exists()) {
      return new HashSet<File>();
    }
    return new HashSet<File>(FileUtils.getFiles(snapshotDirectory, "**", "", false));
  }

}
