package com.example.pagaya.data.backup

import com.example.pagaya.domain.BusinessCalendar
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Contrato del archivo de respaldo, versión de formato 1 y de datos 1 (docs/05 y docs/19).
 * Las claves exportadas son estables y en español; no cambiarlas sin subir la versión.
 */
object BackupFormat {
    const val FORMAT = "paga-ya-backup"
    const val FORMAT_VERSION = 1L
    const val DATA_VERSION = 1L
    const val BUSINESS_ZONE = "America/Bogota"
    const val CURRENCY = "COP"
    const val MIME_TYPE = "application/zip"

    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.json"

    const val VERSION_APP_MAX = 100

    /** Claves del manifiesto, en el orden en que se escriben. */
    object Manifest {
        const val FORMAT = "formato"
        const val FORMAT_VERSION = "version_formato"
        const val DATA_VERSION = "version_datos"
        const val APP_VERSION = "version_app"
        const val BACKUP_ID = "backup_id"
        const val LEDGER_ID = "libreta_id"
        const val CAPTURED_AT = "capturado_en"
        const val ZONE = "zona_negocio"
        const val CURRENCY = "moneda"
        const val DATA_REVISION = "revision_datos"
        const val CLIENTS = "clientes"
        const val MOVEMENTS = "movimientos"
        const val REVISIONS = "revisiones"
        const val DATA_BYTES = "bytes_datos"
        const val DATA_SHA256 = "sha256_datos"

        val ALL = listOf(
            FORMAT, FORMAT_VERSION, DATA_VERSION, APP_VERSION, BACKUP_ID, LEDGER_ID, CAPTURED_AT, ZONE, CURRENCY,
            DATA_REVISION, CLIENTS, MOVEMENTS, REVISIONS, DATA_BYTES, DATA_SHA256,
        )
    }

    /** Claves de `data.json`. Las filas usan los nombres de columna del modelo (docs/03). */
    object Data {
        const val LEDGER = "libreta"
        const val CLIENTS = "clientes"
        const val MOVEMENTS = "movimientos"
        const val REVISIONS = "revisiones"
        val ROOT = listOf(LEDGER, CLIENTS, MOVEMENTS, REVISIONS)

        val LEDGER_KEYS = listOf("libreta_id", "creado_en", "revision_datos", "siguiente_orden_registro")
        val CLIENT_KEYS = listOf("id", "nombre", "telefono", "nota", "creado_en", "modificado_en", "activo")
        val MOVEMENT_KEYS = listOf(
            "id", "cliente_id", "fecha", "orden_registro", "tipo", "valor_cop", "descripcion", "origen",
            "creado_en", "modificado_en", "activo", "version",
        )
        val REVISION_KEYS = listOf(
            "id", "movimiento_id", "version_anterior", "version_nueva", "accion", "estado_anterior_json",
            "cambiado_en", "motivo",
        )
    }

    /**
     * Longitudes máximas al leer, en puntos de código, para textos sin otro límite de producto. Los demás
     * usan ClientRules y MovementRules. Un valor más largo se rechaza durante la lectura, sin construirlo.
     */
    object ReadLimits {
        const val ID = 36
        const val DATE = 10
        const val CODE = 16

        /**
         * `estado_anterior_json`: claves y valores fijos (unos 330 caracteres con los enteros más largos) más
         * una descripción de hasta 1.000 puntos de código, cada uno escapado como mucho a 6 (`\u001f`).
         * Cota: 6.400. Se deja margen.
         */
        const val SNAPSHOT = 8192
    }

    private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    /**
     * `backup_2026-10-05_213045_<UUID>.pagaya.zip`, con la hora de captura en Bogotá. El nombre solo
     * ayuda a reconocer el archivo: no determina identidad, validez ni orden.
     */
    fun fileName(capturedAtMillis: Long, backupId: String): String =
        "backup_" + NAME_TIME.format(Instant.ofEpochMilli(capturedAtMillis).atZone(BusinessCalendar.ZONE)) +
            "_" + backupId + ".pagaya.zip"

    private val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    /** UUID v4 canónico en minúsculas, igual que las restricciones de la base (docs/13). */
    fun isUuid(text: String): Boolean = UUID_V4.matches(text)
}

/**
 * Límites de lectura de docs/05. Se aplican a los bytes realmente leídos, nunca a los tamaños que declara
 * el ZIP. Las pruebas usan límites reducidos para comprobar el mismo comportamiento con archivos pequeños.
 */
data class BackupLimits(
    val maxArchiveBytes: Long = 25L * 1024 * 1024,
    val maxDataBytes: Long = 50L * 1024 * 1024,
    val maxManifestBytes: Long = 64L * 1024,
    val maxClients: Int = 100_000,
    val maxMovements: Int = 100_000,
    val maxRevisions: Int = 100_000,
    val maxJsonDepth: Int = 20,
)

/** Por qué un archivo no es un respaldo válido. Los nombres no contienen datos de la libreta. */
enum class BackupProblem {
    /** No es un ZIP legible, está truncado, cifrado, usa un método no soportado o ZIP64. */
    CONTAINER,

    /** Entradas distintas de exactamente `manifest.json` y `data.json`, repetidas, en otro orden o con rutas. */
    ENTRIES,

    /** Supera un límite de tamaño, de filas o de profundidad. */
    LIMIT_EXCEEDED,

    /** No es UTF-8 válido, tiene BOM o texto Unicode mal formado. */
    ENCODING,
    JSON_SYNTAX,
    DUPLICATE_KEY,

    /** Un valor no tiene el tipo exacto, falta una clave o sobra una. */
    TYPE,

    /** Formato o versión posterior a los que entiende esta versión de la app. */
    UNSUPPORTED_VERSION,

    /** Otro formato, moneda o zona de negocio. */
    INCOMPATIBLE,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    COUNT_MISMATCH,

    /** `libreta_id` o `revision_datos` distintos entre manifiesto y datos. */
    IDENTITY_MISMATCH,

    /** IDs, relaciones, campos obligatorios, valores, fechas u órdenes inválidos. */
    INTEGRITY,

    /** Revisiones discontinuas o snapshots incoherentes con el movimiento. */
    REVISION_CHAIN,

    /** Algún saldo intermedio negativo o desbordado. */
    BALANCE,
    ARCHIVED_WITH_DEBT,

    /** Fechas posteriores al hoy del dispositivo (solo si se pide; la restauración lo comprobará). */
    FUTURE_DATE,
}

/** Archivo inválido. [detail] es diagnóstico técnico: nunca incluye nombres, teléfonos ni importes. */
class BackupInvalidException(val problem: BackupProblem, val detail: String) : RuntimeException("$problem: $detail")

/** Cuenta los bytes realmente leídos y falla en cuanto se supera [max]. */
internal class LimitedInputStream(input: InputStream, private val max: Long, private val what: String) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) add(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) add(n.toLong())
        return n
    }

    override fun skip(n: Long): Long = throw UnsupportedOperationException()

    override fun markSupported(): Boolean = false

    private fun add(n: Long) {
        count += n
        if (count > max) throw BackupInvalidException(BackupProblem.LIMIT_EXCEEDED, "$what supera $max bytes")
    }
}
