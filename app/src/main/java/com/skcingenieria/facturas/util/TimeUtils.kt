package com.skcingenieria.facturas.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

object TimeUtils {
    private val ecLocale: Locale = Locale.forLanguageTag("es-EC")
    private val humanDate = DateTimeFormatter.ofPattern("dd/MM/yyyy", ecLocale)
    private val humanDateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", ecLocale)

    fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    fun todayIso(): String = LocalDate.now().toString()
    fun nowEpochSeconds(): Long = Instant.now().epochSecond

    fun plusMinutesIso(minutes: Int): String = OffsetDateTime.now(ZoneOffset.UTC)
        .plusMinutes(minutes.toLong()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun isDue(iso: String): Boolean = runCatching {
        OffsetDateTime.parse(iso).toInstant() <= Instant.now()
    }.getOrDefault(false)

    fun displayDate(isoDate: String): String = runCatching { LocalDate.parse(isoDate).format(humanDate) }.getOrDefault(isoDate)

    fun displayDateTime(iso: String): String = runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(humanDateTime)
    }.getOrDefault(iso)

    fun isoWeek(isoDate: String): Int = runCatching {
        LocalDate.parse(isoDate).get(WeekFields.ISO.weekOfWeekBasedYear())
    }.getOrDefault(0)

    fun monthName(isoDate: String): String = runCatching {
        val d = LocalDate.parse(isoDate)
        d.month.getDisplayName(java.time.format.TextStyle.FULL, ecLocale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(ecLocale) else it.toString() }
    }.getOrDefault("")
}
