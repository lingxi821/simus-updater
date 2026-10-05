package org.lsposed.lspatch.loader;

import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Exposes the patched app's own private data directory ({@code /data/data/<pkg>}) through the
 * Storage Access Framework, so any file manager that speaks SAF can browse it with no root.
 *
 * <p>The provider runs inside the patched app's process and UID, which is the whole trick: only that
 * UID (or root) may read those files, and this is the one component that runs there and can hand them
 * out. It is declared in the manifest at patch time behind {@code android.permission.MANAGE_DOCUMENTS}
 * -- the platform-signature permission the system Documents UI holds -- so nothing but the system can
 * bind it, and access reaches other apps only through the user granting a document or tree.</p>
 *
 * <p>Document ids are absolute file paths. Every path handed back in is re-checked to sit inside the
 * exported root before it is touched, so a crafted id cannot walk out of the app's own data.</p>
 */
public class LSPatchDocumentsProvider extends DocumentsProvider {

    private static final String ROOT_ID = "lspatch";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_ICON,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
    };

    /** The one directory this provider is allowed to reach, canonicalised once. */
    private File root;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public void attachInfo(android.content.Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        ApplicationInfo appInfo = context.getApplicationInfo();
        try {
            root = new File(appInfo.dataDir).getCanonicalFile();
        } catch (IOException e) {
            root = new File(appInfo.dataDir).getAbsoluteFile();
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        ApplicationInfo appInfo = getContext().getApplicationInfo();
        CharSequence label = appInfo.loadLabel(getContext().getPackageManager());

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_LOCAL_ONLY);
        row.add(Root.COLUMN_TITLE, label != null ? label.toString() : getContext().getPackageName());
        row.add(Root.COLUMN_SUMMARY, getContext().getPackageName());
        row.add(Root.COLUMN_DOCUMENT_ID, docIdForFile(root));
        // The app's own launcher icon, so the root is recognisable in the picker; 0 is a valid
        // "no icon" the framework tolerates.
        row.add(Root.COLUMN_ICON, appInfo.icon);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        addFileRow(result, fileForDocId(documentId));
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File parent = fileForDocId(parentDocumentId);
        File[] children = parent.listFiles();
        if (children != null) {
            for (File child : children) {
                addFileRow(result, child);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileForDocId(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = fileForDocId(parentDocumentId);
        File target = new File(parent, displayName);
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!target.mkdir()) throw new IOException("Failed to mkdir " + target);
            } else {
                if (!target.createNewFile()) throw new IOException("Failed to create " + target);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document: " + e.getMessage());
        }
        return docIdForFile(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = fileForDocId(documentId);
        if (!deleteRecursively(file)) {
            throw new FileNotFoundException("Failed to delete " + documentId);
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File file = fileForDocId(documentId);
        File target = new File(file.getParentFile(), displayName);
        if (!file.renameTo(target)) {
            throw new FileNotFoundException("Failed to rename " + documentId);
        }
        // The id is the path, so a rename mints a new id; returning it tells the framework to
        // re-point rather than keep the stale one.
        return docIdForFile(target);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return mimeTypeOf(fileForDocId(documentId));
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String parent = fileForDocId(parentDocumentId).getCanonicalPath();
            String child = fileForDocId(documentId).getCanonicalPath();
            return child.startsWith(parent.endsWith("/") ? parent : parent + "/");
        } catch (IOException e) {
            return false;
        }
    }

    private void addFileRow(MatrixCursor result, File file) {
        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME
                    | Document.FLAG_SUPPORTS_REMOVE;
        }

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docIdForFile(file));
        // The exported root shows the app's label rather than the raw data-dir basename.
        row.add(Document.COLUMN_DISPLAY_NAME, file.equals(root) ? rootDisplayName() : file.getName());
        row.add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file));
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
    }

    private String rootDisplayName() {
        CharSequence label = getContext().getApplicationInfo().loadLabel(getContext().getPackageManager());
        return label != null ? label.toString() : getContext().getPackageName();
    }

    private String docIdForFile(File file) {
        return file.getAbsolutePath();
    }

    /**
     * Resolves a document id back to a file, refusing anything that would land outside the exported
     * root -- a crafted {@code ../} id cannot reach another app's data or follow a symlink out.
     */
    private File fileForDocId(String documentId) throws FileNotFoundException {
        File file = new File(documentId);
        try {
            String canonical = file.getCanonicalPath();
            String base = root.getCanonicalPath();
            if (!canonical.equals(base) && !canonical.startsWith(base + "/")) {
                throw new FileNotFoundException(documentId + " is outside the exported root");
            }
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to resolve " + documentId + ": " + e.getMessage());
        }
    }

    private static String mimeTypeOf(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }
}
