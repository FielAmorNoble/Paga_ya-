package com.example.pagaya.data.backup

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SyncFailedException

/**
 * Documentos del Storage Access Framework: el URI que devuelve el selector del sistema
 * (`ACTION_CREATE_DOCUMENT`). Solo accede al documento concedido; nunca lo borra.
 */
class ContentResolverDocuments(private val resolver: ContentResolver) : DocumentAccess {

    override fun openForWrite(location: String): OutputStream {
        val uri = location.toUri()
        // "wt" trunca; algunos proveedores solo aceptan "w" (el documento recién creado está vacío).
        val descriptor = try {
            resolver.openFileDescriptor(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: resolver.openFileDescriptor(uri, "w") ?: throw FileNotFoundException("Sin acceso de escritura")
        return SyncingOutputStream(descriptor)
    }

    override fun openForRead(location: String): InputStream =
        resolver.openInputStream(location.toUri()) ?: throw FileNotFoundException("Sin acceso de lectura")

    /**
     * Al cerrar pide al sistema que lleve los datos al almacenamiento (`fsync`) cuando el proveedor lo
     * permite; un proveedor por tubería no lo admite y se cierra sin más. El cierre del descriptor puede
     * informar el error del proveedor, que se propaga.
     */
    private class SyncingOutputStream(private val descriptor: ParcelFileDescriptor) : FileOutputStream(descriptor.fileDescriptor) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                flush()
                try {
                    fd.sync()
                } catch (e: SyncFailedException) {
                    // Tubería u otro descriptor sin sincronización: no hay nada más que hacer.
                }
            } finally {
                try {
                    super.close()
                } finally {
                    descriptor.close()
                }
            }
        }
    }
}
