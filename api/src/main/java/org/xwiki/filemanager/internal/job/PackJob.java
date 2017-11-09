/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.filemanager.internal.job;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.io.IOUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.environment.Environment;
import org.xwiki.filemanager.FileSystem;
import org.xwiki.filemanager.Folder;
import org.xwiki.filemanager.Path;
import org.xwiki.filemanager.job.PackJobStatus;
import org.xwiki.filemanager.job.PackRequest;
import org.xwiki.job.AbstractJob;
import org.xwiki.job.DefaultJobStatus;
import org.xwiki.job.Job;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.model.reference.DocumentReference;

/**
 * Packs multiple files and folders (including the child files and sub-folders) in a single ZIP archive.
 * 
 * @version $Id$
 * @since 2.0M2
 */
@Component
@Named(PackJob.JOB_TYPE + "/actual")
public class PackJob extends AbstractJob<PackRequest, DefaultJobStatus<PackRequest>>
{
    /**
     * The id of the job.
     */
    public static final String JOB_TYPE = "fileManager/pack";

    /**
     * The default URL encoding.
     */
    private static final String UTF8 = "UTF-8";

    /**
     * The module name used when creating temporary files.
     */
    private static final String MODULE_NAME = "filemanager";

    /**
     * The pseudo file system.
     */
    @Inject
    private FileSystem fileSystem;

    /**
     * Used to access the temporary directory.
     */
    @Inject
    private Environment environment;

    /**
     * Wraps the internal {@link DefaultJobStatus} and adds custom data such as the number of bytes written and the size
     * of the output file. We wrap {@link DefaultJobStatus} instead of extending the class because the constructor of
     * {@link DefaultJobStatus} has suffered a breaking change in XCOMMONS-811.
     */
    private PackJobStatus packJobStatus;

    @Override
    public String getType()
    {
        return JOB_TYPE;
    }

    @Override
    protected void runInternal() throws Exception
    {
        Collection<Path> paths = getRequest().getPaths();
        if (paths == null) {
            return;
        }

        File outputFile = getTemporaryFile(getRequest().getOutputFileReference());
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(outputFile));
        String pathPrefix = "";

        this.progressManager.pushLevelProgress(paths.size(), this);

        try {
            for (Path path : paths) {
                this.progressManager.startStep(this);
                pack(path, zip, pathPrefix);
                this.progressManager.endStep(this);
            }
        } finally {
            IOUtils.closeQuietly(zip);
            getPackStatus().setOutputFileSize(outputFile.length());
            this.progressManager.popLevelProgress(this);
        }
    }

    /**
     * Creates a temporary file that can be accessed through the 'temp' action, e.g.:
     * {@code /xwiki/temp/Space/Page/filemanager/file.zip} .
     * 
     * @param fileReference the reference to the temporary file to create
     * @return the temporary file
     * @throws Exception if it fails to create the temporary file
     */
    private File getTemporaryFile(AttachmentReference fileReference) throws Exception
    {
        // Encode to avoid illegal characters in file paths.
        DocumentReference accessDocRef = fileReference.getDocumentReference();
        String encodedWiki = URLEncoder.encode(accessDocRef.getWikiReference().getName(), UTF8);
        String encodedSpace = URLEncoder.encode(accessDocRef.getLastSpaceReference().getName(), UTF8);
        String encodedPage = URLEncoder.encode(accessDocRef.getName(), UTF8);
        String encodedFileName = URLEncoder.encode(fileReference.getName(), UTF8);

        // Create a temporary directory to hold the file.
        String path = String.format("temp/%s/%s/%s/%s/", MODULE_NAME, encodedWiki, encodedSpace, encodedPage);
        File tempDir = new File(this.environment.getTemporaryDirectory(), path);
        if (!((tempDir.exists() || tempDir.mkdirs()) && tempDir.isDirectory() && tempDir.canWrite())) {
            String message = "Failed to create temporary directory [%s].";
            throw new Exception(String.format(message, path));
        }

        File file = new File(tempDir, encodedFileName);
        file.deleteOnExit();
        return file;
    }

    /**
     * Packs a file or a folder.
     * 
     * @param path the file or folder to add to the ZIP archive
     * @param zip the ZIP archive to add the file or folder to
     * @param pathPrefix the current path prefix, used to ensure the folder hierarchy is preserved in the ZIP file
     */
    private void pack(Path path, ZipOutputStream zip, String pathPrefix)
    {
        if (path.getFileReference() != null) {
            packFile(path.getFileReference(), zip, pathPrefix);
        } else if (path.getFolderReference() != null) {
            packFolder(path.getFolderReference(), zip, pathPrefix);
        }
    }

    /**
     * Packs a file.
     * 
     * @param fileReference the file to add to the ZIP archive
     * @param zip the ZIP archive to add the file to
     * @param pathPrefix the file path
     */
    private void packFile(DocumentReference fileReference, ZipOutputStream zip, String pathPrefix)
    {
        org.xwiki.filemanager.File file = fileSystem.getFile(fileReference);
        if (file != null && fileSystem.canView(fileReference)) {
            try {
                String path = pathPrefix + file.getName();
                this.logger.info("Packing file [{}]", path);
                zip.putNextEntry(new ZipArchiveEntry(path));
                long bytesWritten = IOUtils.copyLarge(file.getContent(), zip);
                zip.closeEntry();
                getPackStatus().setBytesWritten(getPackStatus().getBytesWritten() + bytesWritten);
            } catch (IOException e) {
                this.logger.warn("Failed to pack file [{}].", fileReference, e);
            }
        }
    }

    /**
     * Packs a folder.
     * 
     * @param folderReference the folder to add to the ZIP archive
     * @param zip the ZIP archive to add the folder to
     * @param pathPrefix the folder path
     */
    private void packFolder(DocumentReference folderReference, ZipOutputStream zip, String pathPrefix)
    {
        Folder folder = fileSystem.getFolder(folderReference);
        if (folder != null && fileSystem.canView(folderReference)) {
            List<DocumentReference> childFolderReferences = folder.getChildFolderReferences();
            List<DocumentReference> childFileReferences = folder.getChildFileReferences();
            this.progressManager.pushLevelProgress(childFolderReferences.size() + childFileReferences.size() + 1, this);

            try {
                this.progressManager.startStep(this);
                String path = pathPrefix + folder.getName() + '/';
                this.logger.info("Packing folder [{}]", path);
                zip.putNextEntry(new ZipEntry(path));
                zip.closeEntry();
                this.progressManager.endStep(this);

                for (DocumentReference childFolderReference : childFolderReferences) {
                    this.progressManager.startStep(this);
                    packFolder(childFolderReference, zip, path);
                    this.progressManager.endStep(this);
                }

                for (DocumentReference childFileReference : childFileReferences) {
                    this.progressManager.startStep(this);
                    packFile(childFileReference, zip, path);
                    this.progressManager.endStep(this);
                }
            } catch (IOException e) {
                this.logger.warn("Failed to pack folder [{}].", folderReference, e);
            } finally {
                this.progressManager.popLevelProgress(this);
            }
        }
    }

    /**
     * @return the extended job status
     */
    public PackJobStatus getPackStatus()
    {
        // The internal AbstractJob and AbstractJobStatus classes have been refactored in XWiki 7.4M1 by XCOMMONS-880
        // which seems to have broken the runtime compatibility in the sense that some protected fields and some public
        // methods are not accessible anymore after they have been moved higher in the class hierarchy (and in a
        // different package). The workaround I found is to cast 'this' to the interface/class that provides the public
        // method I want to access.
        JobStatus defaultJobStatus = ((Job) this).getStatus();
        if (this.packJobStatus == null && defaultJobStatus != null) {
            this.packJobStatus = new PackJobStatus(defaultJobStatus);
        }
        return this.packJobStatus;
    }
}
