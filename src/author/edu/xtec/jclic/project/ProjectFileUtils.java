/*
 * File    : ProjectFileUtils.java
 * Created : 10-aug-2015 09:00
 * By      : fbusquets
 *
 * JClic - Authoring and playing system for educational activities
 *
 * Copyright (C) 2000 - 2005 Francesc Busquets & Departament
 * d'Educacio de la Generalitat de Catalunya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details (see the LICENSE file).
 */
package edu.xtec.jclic.project;

import edu.xtec.jclic.bags.ActivityBagElement;
import edu.xtec.jclic.bags.ActivitySequenceElement;
import edu.xtec.jclic.bags.JumpInfo;
import edu.xtec.jclic.bags.MediaBagElement;
import edu.xtec.jclic.fileSystem.FileSystem;
import edu.xtec.jclic.fileSystem.FileZip;
import edu.xtec.util.JDomUtility;
import edu.xtec.util.ResourceBridge;
import edu.xtec.util.StreamIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import org.json.JSONObject;

/**
 * Miscellaneous utilities to process ".jclic.zip" files, normalizing media file
 * names, avoiding links to other "zip" files and extracting contents to a given
 * folder.
 *
 * @author fbusquet
 */
public class ProjectFileUtils implements ResourceBridge {

  FileZip zipFS;
  String zipFilePath;
  String zipFileName;
  String projectName;
  String jclicFileName;
  String basePath;
  String relativePath;
  String[] entries;
  JClicProject project;

  // Interruption flag
  public static boolean interrupt = false;

  /**
   * Builds a ProjectFileUtils object, initializing a @link{JClicProject}
   *
   * @param fileName - Relative or absolute path to the ".jclic.zip" file to be
   * processed
   * @param basePath - Base path of this project. Relative paths are based on
   * this one. When null, the parent folder of fileName will be used.
   * @throws Exception
   */
  public ProjectFileUtils(String fileName, String basePath) throws Exception {

    zipFilePath = new File(fileName).getCanonicalPath();
    if (!zipFilePath.endsWith(".jclic.zip")) {
      throw new Exception("File " + fileName + " is not a jclic.zip file!");
    }
    
    String zipBase = (new File(zipFilePath)).getParent();
    this.basePath = (basePath == null ? zipBase : basePath);    
    relativePath = this.basePath.equals(zipBase) ? "" : zipBase.substring(this.basePath.length()+1);
    
    zipFS = (FileZip) FileSystem.createFileSystem(zipFilePath, this);
    zipFileName = zipFS.getZipName();
    jclicFileName = zipFileName.substring(0, zipFileName.lastIndexOf("."));

    entries = zipFS.getEntries(null);

    String[] projects = zipFS.getEntries(".jclic");
    if (projects == null) {
      throw new Exception("File " + zipFilePath + " does not contain any jclic project");
    }
    projectName = projects[0];

    org.jdom.Document doc = zipFS.getXMLDocument(projectName);
    project = JClicProject.getJClicProject(doc.getRootElement(), this, zipFS, zipFileName);

  }

  /**
   * Normalizes the file names of the media bag, restricting it to URL-safe
   * characters.
   *
   * @param ps - The @link{PrintStream} where progress messages will be
   * outputed. Can be null.
   * @throws java.lang.InterruptedException
   */
  public void normalizeFileNames(PrintStream ps) throws InterruptedException {

    HashSet<String> currentNames = new HashSet<String>();
    Iterator<MediaBagElement> it = project.mediaBag.getElements().iterator();
    while (it.hasNext()) {

      if (interrupt) {
        interrupt = false;
        throw new InterruptedException();
      }

      MediaBagElement mbe = it.next();

      String fn = mbe.getFileName();
      mbe.setMetaData(fn);
      String fnv = FileSystem.getValidFileName(fn);
      // Avoid filenames starting with a dot
      if (fnv.charAt(0) == '.') {
        fnv = "_" + fnv;
      }
      if (!fnv.equals(fn)) {
        String fn0 = fnv;
        int n = 0;
        while (currentNames.contains(fnv)) {
          fnv = Integer.toString(n++) + fn0;
        }
        if (ps != null) {
          ps.println("Renaming \"" + fn + "\" as \"" + fnv + "\"");
        }
        mbe.setFileName(fnv);
      }
      currentNames.add(fnv);
    }
  }

  /**
   * Searchs for links to ".jclic.zip" files in @link{ActiveBox} 
   * and @link{JumpInfo} objects, and redirects it to ".jclic" files
   *
   * @param ps - The @link{PrintStream} where progress messages will be
   * outputed. Can be null.
   * @throws java.lang.InterruptedException
   */
  public void avoidZipLinks(PrintStream ps) throws InterruptedException {
    // Scan Activity elements
    for (ActivityBagElement ab : project.activityBag.getElements()) {
      if (interrupt) {
        interrupt = false;
        throw new InterruptedException();
      }
      avoidZipLinksInElement(ab.getData(), ps);
    }

    for (ActivitySequenceElement ase : project.activitySequence.getElements()) {

      if (interrupt) {
        interrupt = false;
        throw new InterruptedException();
      }

      if (ase.fwdJump != null) {
        avoidZipLinksInJumpInfo(ase.fwdJump, ps);
        avoidZipLinksInJumpInfo(ase.fwdJump.upperJump, ps);
        avoidZipLinksInJumpInfo(ase.fwdJump.lowerJump, ps);
      }
      if (ase.backJump != null) {
        avoidZipLinksInJumpInfo(ase.backJump, ps);
        avoidZipLinksInJumpInfo(ase.backJump.upperJump, ps);
        avoidZipLinksInJumpInfo(ase.backJump.lowerJump, ps);
      }
    }
  }

  /**
   * Searchs for ".jclic.zip" links in JumpInfo elements, changing it to links
   * to plain ".jclic" files.
   *
   * @param ji - The JumpInfo to scan for links
   * @param ps - The @link{PrintStream} where progress messages will be
   * outputed. Can be null.
   * @throws java.lang.InterruptedException
   */
  public void avoidZipLinksInJumpInfo(JumpInfo ji, PrintStream ps) throws InterruptedException {
    if (ji != null && ji.projectPath != null && ji.projectPath.endsWith(".jclic.zip")) {
      String p = ji.projectPath;
      String pv = p.substring(0, p.length() - 4);
      ji.projectPath = pv;
      if (ps != null) {
        ps.println("Changing sequence link from \"" + p + "\" to \"" + pv + "\"");
      }
    }
  }

  /**
   *
   * Searchs for links to ".jclic.zip" files in the given JDOM element. This
   * method makes recursive calls on all the child elements of the provided
   * starting point.
   *
   * @param el - The org.jdom.Element to scan for links
   * @param ps - The @link{PrintStream} where progress messages will be
   * outputed. Can be null.
   * @throws java.lang.InterruptedException
   */
  public void avoidZipLinksInElement(org.jdom.Element el, PrintStream ps) throws InterruptedException {
    if (el.getAttribute("params") != null) {
      String p = el.getAttributeValue("params");
      if (p != null && p.endsWith(".jclic.zip")) {
        String pv = p.substring(0, p.length() - 4);
        if (ps != null) {
          ps.println("Changing media link from \"" + p + "\" to \"" + pv + "\"");
        }
        el.setAttribute("params", pv);
      }
    }
    Iterator it = el.getChildren().iterator();
    while (it.hasNext()) {

      if (interrupt) {
        interrupt = false;
        throw new InterruptedException();
      }

      avoidZipLinksInElement((org.jdom.Element) it.next(), ps);
    }
  }
  
  public String getRelativeFn(String fName){
    return relativePath.length()>0 ? relativePath + '/' + fName : fName;
  }

  /**
   * Saves the JClic project and all its contents in plain format (not zipped)
   * into the specified path
   *
   * @param path - The path where the project will be saved
   * @param ps - The @link{PrintStream} where progress messages will be
   * outputed. Can be null.
   * @throws Exception
   * @throws java.lang.InterruptedException
   */
  public void saveTo(String path, Collection<String> fileList, PrintStream ps) throws Exception, InterruptedException {

    File outPath = new File(path);
    path = outPath.getCanonicalPath();

    // Check outPath exists and is writtable
    if (!outPath.exists()) {
      outPath.mkdirs();
    }

    if (!outPath.isDirectory() || !outPath.canWrite()) {
      throw new Exception("Unable to write to: \"" + path + "\"");
    }

    // Export media fileList
    Iterator<MediaBagElement> it = project.mediaBag.getElements().iterator();
    while (it.hasNext()) {

      if (interrupt) {
        interrupt = false;
        throw new InterruptedException();
      }

      MediaBagElement mbe = it.next();
      String fn = mbe.getMetaData();
      if (fn == null) {
        fn = mbe.getFileName();
      }

      InputStream is = zipFS.getInputStream(fn);
      File outFile = new File(outPath, mbe.getFileName());
      FileOutputStream fos = new FileOutputStream(outFile);
      if (ps != null) {
        ps.println("Extracting " + fn + " to " + outFile.getCanonicalPath());
      }
      StreamIO.writeStreamTo(is, fos);
      
      if(fileList!=null){
        fileList.add(getRelativeFn(outFile.getName()));
      }
    }

    // Save ".jclic" file
    org.jdom.Document doc = project.getDocument();

    File outFile = new File(outPath, jclicFileName);
    FileOutputStream fos = new FileOutputStream(outFile);
    if (ps != null) {
      ps.println("Saving project to: " + outFile.getCanonicalPath());
    }
    JDomUtility.saveDocument(fos, doc);
    fos.close();
    
    // Save ".jclic.js" file
    String jsFileName = jclicFileName + ".js";
    outFile = new File(outPath, jsFileName);
    fos = new FileOutputStream(outFile);
    PrintWriter pw = new PrintWriter(new OutputStreamWriter(fos, "UTF-8"));
    if (ps != null) {
      ps.println("Saving project to: " + outFile.getCanonicalPath());
    }

    org.jdom.output.XMLOutputter xmlOutputter = new org.jdom.output.XMLOutputter();
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    xmlOutputter.output(doc, bas);
    JSONObject json = new JSONObject();
    json.put("xml", bas.toString("UTF-8"));

    String sequence = json.toString();
    sequence = sequence.substring(8, sequence.length() - 2);
    
    pw.println("if(JClicObject){JClicObject.projectFiles[\"" + getRelativeFn(jclicFileName) + "\"]=\"" + sequence + "\";}");
    pw.flush();
    pw.close();
    
    if(fileList!=null){
      fileList.add(getRelativeFn(jclicFileName));
      fileList.add(getRelativeFn(jsFileName));      
    }

    if (ps != null) {
      ps.println("Done processing: " + zipFilePath);
    }
  }

  public static void processSingleFile(String sourceFile, String destPath, Collection<String> fileList, PrintStream ps) throws Exception, InterruptedException {
    processSingleFile(sourceFile, destPath, null, fileList, ps);
  }

  public static void processSingleFile(String sourceFile, String destPath, String basePath, Collection<String> fileList, PrintStream ps) throws Exception, InterruptedException {
    ProjectFileUtils prjFU = new ProjectFileUtils(sourceFile, basePath);
    prjFU.normalizeFileNames(ps);
    prjFU.avoidZipLinks(ps);
    prjFU.saveTo(destPath, fileList, ps);
  }

  public static void processRootFolder(String sourcePath, String destPath, Collection<String> fileList, PrintStream ps) throws Exception, InterruptedException {
    String basePath = (new File(sourcePath)).getCanonicalPath();
    processFolder(sourcePath, destPath, basePath, fileList, ps);
  }

  public static void processFolder(String sourcePath, String destPath, String basePath, Collection<String> fileList, PrintStream ps) throws Exception, InterruptedException {

    File src = new File(sourcePath);

    if (!src.isDirectory() || !src.canRead()) {
      throw new Exception("Source directory \"" + sourcePath + "\" does not exist, not a directory or not readable");
    }

    if (ps != null) {
      ps.println("Exporting all jclic.zip files in \"" + src.getCanonicalPath() + "\" to \"" + destPath + "\"");
    }

    File dest = new File(destPath);

    File[] jclicZipFiles = src.listFiles(new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.toLowerCase().endsWith(".jclic.zip");
      }
    });

    for (File f : jclicZipFiles) {

      if (interrupt) {
        interrupt = false;
        throw new InterruptedException();
      }

      if (ps != null) {
        ps.println("\nProcessing file: " + f.getAbsolutePath());
      }

      processSingleFile(f.getAbsolutePath(), dest.getAbsolutePath(), basePath, fileList, ps);

    }

    // Force garbage collection
    jclicZipFiles = null;
    System.gc();

    // Process subdirectories
    File[] subDirs = src.listFiles(new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return new File(dir, name).isDirectory();
      }
    });

    for (File f : subDirs) {

      if (interrupt) {
        interrupt = false;
        throw new InterruptedException();
      }

      ProjectFileUtils.processFolder(
              new File(src, f.getName()).getCanonicalPath(),
              new File(dest, f.getName()).getCanonicalPath(),
              basePath,
              fileList,
              ps);
    }

    // Force garbage collection
    subDirs = null;
    System.gc();
  }

  // Void implementation of "ResourceBridge" methods:
  //
  public java.io.InputStream getProgressInputStream(java.io.InputStream is, int expectedLength, String name) {
    return is;
  }

  public edu.xtec.util.Options getOptions() {
    return null;
  }

  public String getMsg(String key) {
    return key;
  }

  public javax.swing.JComponent getComponent() {
    return null;
  }

  public void displayUrl(String url, boolean inFrame) {
    throw new UnsupportedOperationException("Not supported");
  }
}
