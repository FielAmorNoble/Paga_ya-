package com.example.pagaya.data.local

/**
 * Restricciones de fila que las anotaciones de Room no pueden expresar (CHECK), implementadas
 * como triggers `BEFORE INSERT/UPDATE/DELETE` que abortan con `RAISE(ABORT, 'PY_CHECK:<tabla>.<regla>')`.
 *
 * Room no crea, exporta ni valida triggers: se crean en [PagaYaDatabase] junto al esquema y al
 * abrir se exige que coincidan exactamente con [definitions]. Toda migración futura debe dejar
 * esas mismas definiciones (docs/13_Persistencia_Local.md).
 *
 * Solo cubren reglas de una fila. Saldo, prefijos cronológicos, continuidad de versiones y
 * auditoría transaccional corresponden al repositorio.
 */
object SchemaConstraints {

    const val TRIGGER_PREFIX = "pagaya_"
    const val ERROR_PREFIX = "PY_CHECK:"

    private enum class Event { INSERT, UPDATE, DELETE }

    private class Check(val table: String, val rule: String, val condition: String, val events: List<Event>)

    private val insertAndUpdate = listOf(Event.INSERT, Event.UPDATE)

    // UUID v4 canónico en minúsculas (8-4-4-4-12).
    private val uuidGlob = buildString {
        val hex = "[0-9a-f]"
        append(hex.repeat(8)).append('-')
        append(hex.repeat(4)).append("-4")
        append(hex.repeat(3)).append("-[89ab]")
        append(hex.repeat(3)).append('-')
        append(hex.repeat(12))
    }

    private fun uuid(col: String) = "(typeof($col) = 'text' AND $col GLOB '$uuidGlob')"
    private fun text(col: String, min: Int, max: Int) = "(typeof($col) = 'text' AND length($col) BETWEEN $min AND $max)"
    private fun optionalText(col: String, min: Int, max: Int) = "($col IS NULL OR ${text(col, min, max)})"
    private fun trimmedText(col: String, min: Int, max: Int) = "(${text(col, min, max)} AND $col = trim($col))"
    private fun integerAtLeast(col: String, min: Long) = "(typeof($col) = 'integer' AND $col >= $min)"
    private fun boolean(col: String) = "(typeof($col) = 'integer' AND $col IN (0, 1))"
    private fun oneOf(col: String, values: List<String>) =
        "(typeof($col) = 'text' AND $col IN (${values.joinToString { "'$it'" }}))"
    private fun unchanged(vararg cols: String) = cols.joinToString(" AND ", "(", ")") { "NEW.$it IS OLD.$it" }

    /**
     * Fecha `YYYY-MM-DD` existente en el calendario gregoriano. Se calcula explícitamente porque
     * `date()` no es portable: SQLite 3.44.5 de Android 15 devuelve '2026-02-30' sin normalizarlo.
     */
    private fun calendarDate(col: String): String {
        val year = "CAST(substr($col, 1, 4) AS INTEGER)"
        val month = "CAST(substr($col, 6, 2) AS INTEGER)"
        val day = "CAST(substr($col, 9, 2) AS INTEGER)"
        val leap = "(($year % 4 = 0 AND $year % 100 <> 0) OR $year % 400 = 0)"
        val daysInMonth = "CASE $month WHEN 2 THEN CASE WHEN $leap THEN 29 ELSE 28 END " +
            "WHEN 4 THEN 30 WHEN 6 THEN 30 WHEN 9 THEN 30 WHEN 11 THEN 30 ELSE 31 END"
        return "(typeof($col) = 'text' AND $col GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' " +
            "AND $month BETWEEN 1 AND 12 AND $day BETWEEN 1 AND $daysInMonth)"
    }

    private val checks: List<Check> = buildList {
        fun add(table: String, rule: String, condition: String, events: List<Event> = insertAndUpdate) =
            add(Check(table, rule, condition, events))

        "cliente".let { t ->
            add(t, "id", uuid("NEW.id"))
            add(t, "nombre", trimmedText("NEW.nombre", 1, 120))
            add(t, "nombre_busqueda", "(typeof(NEW.nombre_busqueda) = 'text')")
            add(t, "telefono", optionalText("NEW.telefono", 1, 30))
            add(t, "nota", optionalText("NEW.nota", 1, 1000))
            add(t, "instantes", "(${integerAtLeast("NEW.creado_en", 0)} AND ${integerAtLeast("NEW.modificado_en", 0)})")
            add(t, "activo", boolean("NEW.activo"))
            add(t, "inmutables", unchanged("id", "creado_en"), listOf(Event.UPDATE))
        }

        "movimiento".let { t ->
            add(t, "id", uuid("NEW.id"))
            add(t, "fecha", calendarDate("NEW.fecha"))
            add(t, "orden_registro", integerAtLeast("NEW.orden_registro", 1))
            add(t, "tipo", oneOf("NEW.tipo", MovementType.entries.map { it.name }))
            add(t, "origen", oneOf("NEW.origen", MovementOrigin.entries.map { it.name }))
            // PAGO_TOTAL solo acompaña a ABONO; FIADO siempre es MANUAL. Se limita a la combinación
            // para no solaparse con las reglas de tipo y origen: SQLite no garantiza el orden de los triggers.
            add(t, "tipo_origen", "(NEW.tipo IS NOT 'FIADO' OR NEW.origen IS NOT 'PAGO_TOTAL')")
            add(t, "valor_cop", "(typeof(NEW.valor_cop) = 'integer' AND NEW.valor_cop BETWEEN 1 AND 999999999)")
            add(t, "descripcion", optionalText("NEW.descripcion", 1, 1000))
            add(t, "instantes", "(${integerAtLeast("NEW.creado_en", 0)} AND ${integerAtLeast("NEW.modificado_en", 0)})")
            add(t, "activo", boolean("NEW.activo"))
            add(t, "version", integerAtLeast("NEW.version", 1))
            add(
                t,
                "inmutables",
                unchanged("id", "cliente_id", "tipo", "orden_registro", "creado_en"),
                listOf(Event.UPDATE),
            )
        }

        "movimiento_revision".let { t ->
            val insertOnly = listOf(Event.INSERT)
            add(t, "id", uuid("NEW.id"), insertOnly)
            add(
                t,
                "versiones",
                "(${integerAtLeast("NEW.version_anterior", 1)} AND typeof(NEW.version_nueva) = 'integer' " +
                    "AND NEW.version_nueva = NEW.version_anterior + 1)",
                insertOnly,
            )
            add(t, "accion", oneOf("NEW.accion", RevisionAction.entries.map { it.name }), insertOnly)
            add(
                t,
                "estado_anterior_json",
                "(typeof(NEW.estado_anterior_json) = 'text' AND substr(NEW.estado_anterior_json, 1, 1) = '{' " +
                    "AND substr(NEW.estado_anterior_json, -1, 1) = '}')",
                insertOnly,
            )
            add(t, "cambiado_en", integerAtLeast("NEW.cambiado_en", 0), insertOnly)
            add(t, "motivo", trimmedText("NEW.motivo", 1, 300), insertOnly)
            // Las revisiones son inmutables; el borrado queda reservado a la restauración completa.
            add(t, "inmutable", "0", listOf(Event.UPDATE))
        }

        "libreta_metadata".let { t ->
            add(t, "fila_unica", "(typeof(NEW.id) = 'integer' AND NEW.id = ${LedgerMetadataEntity.SINGLE_ROW_ID})")
            add(t, "libreta_id", uuid("NEW.libreta_id"))
            add(t, "revision_datos", integerAtLeast("NEW.revision_datos", 0))
            add(t, "siguiente_orden_registro", integerAtLeast("NEW.siguiente_orden_registro", 1))
            add(t, "creado_en", integerAtLeast("NEW.creado_en", 0))
            add(t, "epoca_local", uuid("NEW.epoca_local"))
            add(
                t,
                "restauracion",
                "((NEW.ultima_restauracion_id IS NULL AND NEW.ultima_restauracion_en IS NULL) OR " +
                    "(${uuid("NEW.ultima_restauracion_id")} AND ${integerAtLeast("NEW.ultima_restauracion_en", 0)}))",
            )
            add(t, "copia_recuperacion_localizador", optionalText("NEW.copia_recuperacion_localizador", 1, 2048))
            add(t, "sin_borrado", "0", listOf(Event.DELETE))
        }

        "backup_registro".let { t ->
            add(t, "backup_id", uuid("NEW.backup_id"))
            add(t, "destino", oneOf("NEW.destino", BackupDestination.entries.map { it.name }))
            add(t, "libreta_id", uuid("NEW.libreta_id"))
            add(t, "epoca_local", uuid("NEW.epoca_local"))
            add(t, "revision_datos", integerAtLeast("NEW.revision_datos", 0))
            add(t, "capturado_en", integerAtLeast("NEW.capturado_en", 0))
            add(t, "estado", oneOf("NEW.estado", BackupState.entries.map { it.name }))
            // verificado_en existe si y solo si el estado es VERIFICADO.
            add(
                t,
                "verificacion",
                "((NEW.estado IS 'VERIFICADO' AND ${integerAtLeast("NEW.verificado_en", 0)}) OR " +
                    "(NEW.estado IS NOT 'VERIFICADO' AND NEW.verificado_en IS NULL))",
            )
            add(t, "localizador", optionalText("NEW.localizador", 1, 2048))
            add(
                t,
                "cuenta_drive_huella",
                "(NEW.cuenta_drive_huella IS NULL OR (NEW.destino = 'DRIVE' AND ${text("NEW.cuenta_drive_huella", 1, 128)}))",
            )
            // Solo códigos, nunca texto libre que pueda contener datos financieros.
            add(
                t,
                "error_codigo",
                "(NEW.error_codigo IS NULL OR (${text("NEW.error_codigo", 1, 64)} AND NEW.error_codigo NOT GLOB '*[^A-Z0-9_]*'))",
            )
            add(
                t,
                "inmutables",
                unchanged("backup_id", "destino", "libreta_id", "epoca_local", "revision_datos", "capturado_en"),
                listOf(Event.UPDATE),
            )
        }
    }

    private fun triggerName(check: Check, event: Event) =
        "$TRIGGER_PREFIX${check.table}_${check.rule}_${event.name.lowercase()}"

    /** Nombre del trigger → sentencia `CREATE TRIGGER` exacta de la versión de esquema actual. */
    val definitions: Map<String, String> = checks.flatMap { check ->
        check.events.map { event ->
            // `IS NOT 1` también rechaza una condición NULL.
            triggerName(check, event) to
                "CREATE TRIGGER ${triggerName(check, event)} BEFORE ${event.name} ON ${check.table} " +
                "FOR EACH ROW WHEN (${check.condition}) IS NOT 1 " +
                "BEGIN SELECT RAISE(ABORT, '$ERROR_PREFIX${check.table}.${check.rule}'); END"
        }
    }.toMap()

    val createStatements: List<String> get() = definitions.values.toList()
}
