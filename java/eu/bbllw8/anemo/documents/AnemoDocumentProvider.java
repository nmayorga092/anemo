/*
 * Copyright (c) 2021 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package eu.bbllw8.anemo.documents;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class AnemoDocumentProvider extends DocumentsProvider {
    private static final String TAG = "AnemoDocumentProvider";
    private static final String AUTHORITY = "eu.bbllw8.anemo.documents";

    private static final String[] DEFAULT_ROOT_PROJECTION = {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_TITLE,
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_FLAGS,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
    };

    private static final int MAX_SEARCH_RESULTS = 20;
    private static final int MAX_LAST_MODIFIED = 5;

    private static final String ROOT = "anemo";
    private static final String DOCUMENTS = "Documents";
    private static final String PICTURES = "Pictures";
    private static final String MOVIES = "Movies";
    private static final String MUSIC = "Music";

    private File baseDir;
    private File documentsDir;
    private File picturesDir;
    private File moviesDir;
    private File musicDir;

    private ContentResolver cr;

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        cr = context.getContentResolver();
        return prepareDirectories(context);
    }

    /* Query */

    @NonNull
    @Override
    public Cursor queryRoots(@NonNull String[] projection) {
        final Context context = getContext();
        final MatrixCursor result = new MatrixCursor(rootProjection(projection));
        final MatrixCursor.RowBuilder row = result.newRow();

        row.add(Root.COLUMN_ROOT_ID, ROOT);
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(baseDir));
        row.add(Root.COLUMN_AVAILABLE_BYTES, baseDir.getFreeSpace());
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE
                | Root.FLAG_SUPPORTS_RECENTS
                | Root.FLAG_SUPPORTS_SEARCH);
        row.add(Root.COLUMN_ICON, R.drawable.ic_storage);
        row.add(Root.COLUMN_MIME_TYPES, AnemoUtils.getChildMimeTypes(baseDir));
        row.add(Root.COLUMN_TITLE, context.getString(R.string.app_name));
        row.add(Root.COLUMN_SUMMARY, context.getString(R.string.anemo_description));
        return result;
    }

    @NonNull
    @Override
    public Cursor queryDocument(@NonNull String documentId,
                                @Nullable String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(documentProjection(projection));
        includeFile(result, documentId);
        return result;
    }

    @NonNull
    @Override
    public Cursor queryChildDocuments(@NonNull String parentDocumentId,
                                      @NonNull String[] projection,
                                      @Nullable String sortOrder)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(documentProjection(projection));
        final File parent = getFileForId(parentDocumentId);
        final File[] children = parent.listFiles();
        if (children != null) {
            for (final File file : children) {
                includeFile(result, file);
            }
        }

        if (baseDir.equals(parent)) {
            // Show info in root dir
            final Bundle extras = new Bundle();
            extras.putCharSequence(DocumentsContract.EXTRA_INFO,
                    getContext().getText(R.string.anemo_info));
            result.setExtras(extras);
        }
        return result;
    }

    @NonNull
    @Override
    public Cursor queryRecentDocuments(@NonNull String rootId,
                                       @NonNull String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(documentProjection(projection));
        AnemoUtils.getLastModifiedFiles(getFileForId(rootId), MAX_LAST_MODIFIED)
                .forEach(file -> includeFile(result, file));
        return result;
    }

    @NonNull
    @Override
    public Cursor querySearchDocuments(@NonNull String rootId,
                                       @NonNull String query,
                                       @Nullable String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(documentProjection(projection));
        AnemoUtils.queryFiles(getFileForId(rootId), query, MAX_SEARCH_RESULTS)
                .forEach(file -> includeFile(result, file));
        return result;
    }

    /* Open */

    @NonNull
    @Override
    public ParcelFileDescriptor openDocument(@NonNull String documentId,
                                             @NonNull String mode,
                                             @Nullable CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(getFileForId(documentId),
                ParcelFileDescriptor.parseMode(mode));
    }

    @NonNull
    @Override
    public AssetFileDescriptor openDocumentThumbnail(@NonNull String documentId,
                                                     @Nullable Point sizeHint,
                                                     @Nullable CancellationSignal signal)
            throws FileNotFoundException {
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(getFileForId(documentId),
                ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, pfd.getStatSize());
    }


    /* Manage */

    @Nullable
    @Override
    public String createDocument(@NonNull String documentId,
                                 @NonNull String mimeType,
                                 @NonNull String displayName)
            throws FileNotFoundException {
        final File parent = getFileForId(documentId);
        final File file = new File(parent.getPath(), displayName);

        try {
            final boolean createFileSuccess = Document.MIME_TYPE_DIR.equals(mimeType)
                    ? file.mkdir()
                    : file.createNewFile();
            if (createFileSuccess
                    && file.setWritable(true)
                    && file.setReadable(true)) {
                documentId = getDocIdForFile(file);
                notifyChange(documentId);
                return documentId;
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document with name "
                    + displayName + " and documentId " + documentId);
        }
        return null;
    }

    @Override
    public void deleteDocument(@NonNull String documentId) throws FileNotFoundException {
        final File file = getFileForId(documentId);
        if (!file.delete()) {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
        notifyChange(documentId);
    }

    @NonNull
    @Override
    public String copyDocument(@NonNull String sourceDocumentId,
                               @NonNull String targetParentDocumentId)
            throws FileNotFoundException {
        final File source = getFileForId(sourceDocumentId);
        final File targetParent = getFileForId(targetParentDocumentId);
        final File target = new File(targetParent, source.getName());

        try {
            Files.copy(source.toPath(),
                    target.toPath(),
                    StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to copy " + sourceDocumentId
                    + " to " + targetParentDocumentId + ": " + e.getMessage());
        }

        notifyChange(targetParentDocumentId);
        return getDocIdForFile(target);
    }

    @NonNull
    @Override
    public String moveDocument(@NonNull String sourceDocumentId,
                               @NonNull String sourceParentDocumentId,
                               @NonNull String targetParentDocumentId)
            throws FileNotFoundException {
        final File source = getFileForId(sourceDocumentId);
        final File targetParent = getFileForId(targetParentDocumentId);
        final File target = new File(targetParent, source.getName());

        try {
            Files.move(source.toPath(),
                    target.toPath(),
                    StandardCopyOption.COPY_ATTRIBUTES,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to move " + sourceDocumentId
                + " to " + targetParentDocumentId + ": " + e.getMessage());
        }

        notifyChange(sourceDocumentId);
        notifyChange(targetParentDocumentId);
        return getDocIdForFile(target);
    }

    @NonNull
    @Override
    public String renameDocument(@NonNull String documentId,
                                 @NonNull String displayName)
            throws FileNotFoundException {
        final File file = getFileForId(documentId);
        final File target = new File(file.getParent(), displayName);

        try {
            Files.move(file.toPath(), target.toPath());
        } catch (IOException e) {
            throw new FileNotFoundException("Couldn't rename " + documentId
                    + " to " + displayName);
        }

        final String targetDocumentId = getDocIdForFile(target);
        notifyChange(targetDocumentId);
        return targetDocumentId;
    }

    @NonNull
    @Override
    public String getDocumentType(@NonNull String documentId) throws FileNotFoundException {
        return AnemoUtils.getTypeForFile(getFileForId(documentId));
    }

    /* Setup */

    private boolean prepareDirectories(@NonNull Context context) {
        baseDir = new File(context.getFilesDir(), ROOT);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            Log.e(TAG, "Failed to prepare root directory");
            return false;
        }
        documentsDir = new File(baseDir, DOCUMENTS);
        if (!documentsDir.exists() && !documentsDir.mkdir()) {
            Log.e(TAG, "Failed to prepare Documents directory");
            return false;
        }
        picturesDir = new File(baseDir, PICTURES);
        if (!picturesDir.exists() && !picturesDir.mkdir()) {
            Log.e(TAG, "Failed to prepare Pictures directory");
            return false;
        }
        moviesDir = new File(baseDir, MOVIES);
        if (!moviesDir.exists() && !moviesDir.mkdir()) {
            Log.e(TAG, "Failed to prepare Movies directory");
            return false;
        }
        musicDir = new File(baseDir, MUSIC);
        if (!musicDir.exists() && !musicDir.mkdir()) {
            Log.e(TAG, "Failed to prepare Music directory");
            return false;
        }
        return true;
    }

    /* Projection */

    @NonNull
    private static String[] rootProjection(@Nullable String[] projection) {
        return projection != null
                ? projection
                : DEFAULT_ROOT_PROJECTION;
    }

    @NonNull
    private static String[] documentProjection(@Nullable String[] projection) {
        return projection == null
                ? DEFAULT_DOCUMENT_PROJECTION
                : projection;
    }

    /* Results */

    private void includeFile(@NonNull MatrixCursor result,
                             @NonNull String docId)
            throws FileNotFoundException {
        includeFile(result, docId, getFileForId(docId));
    }

    private void includeFile(@NonNull MatrixCursor result,
                             @NonNull File file) {
        includeFile(result, getDocIdForFile(file), file);
    }

    private void includeFile(@NonNull MatrixCursor result,
                             @NonNull String docId,
                             @NonNull File file) {
        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;

                // Additional features for user-created directories
                if (!file.equals(baseDir)
                        && !file.equals(documentsDir)
                        && !file.equals(picturesDir)
                        && !file.equals(moviesDir)
                        && !file.equals(musicDir)) {
                    flags |= Document.FLAG_SUPPORTS_RENAME
                            | Document.FLAG_SUPPORTS_DELETE
                            | Document.FLAG_SUPPORTS_MOVE;
                }
            }
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE
                    | Document.FLAG_SUPPORTS_DELETE;
        }

        final String fileName = file.getName();
        final String mimeType = AnemoUtils.getTypeForFile(file);

        if (mimeType.startsWith("image/")) {
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, fileName);
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
    }

    /* Document ids */

    @NonNull
    private String getDocIdForFile(@NonNull File file) {
        String path = file.getAbsolutePath();
        final String rootPath = baseDir.getPath();
        if (rootPath.equals(path)) {
            path = "";
        } else if (rootPath.endsWith("/")) {
            path = path.substring(rootPath.length());
        } else {
            path = path.substring(rootPath.length() + 1);
        }

        return ROOT + ':' + path;
    }

    @NonNull
    private File getFileForId(@NonNull String documentId) throws FileNotFoundException {
        if (documentId.equals(ROOT)) {
            return baseDir;
        }

        final int splitIndex = documentId.indexOf(':', 1);
        if (splitIndex < 0) {
            throw new FileNotFoundException("No root for " + documentId);
        }

        final String targetPath = documentId.substring(splitIndex + 1);
        final File target = new File(baseDir, targetPath);
        if (!target.exists()) {
            throw new FileNotFoundException("No file for " + documentId + " at " + target);
        }
        return target;
    }

    /* Notify */

    private void notifyChange(@NonNull String documentId) {
        cr.notifyChange(DocumentsContract.buildDocumentUri(AUTHORITY, documentId), null);
    }
}
