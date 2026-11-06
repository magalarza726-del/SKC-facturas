package com.skcingenieria.facturas.util

import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object CodeUtils {
    fun uuid(): String = UUID.randomUUID().toString()

    fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .uppercase(Locale.ROOT)
        .replace("[^A-Z0-9]+".toRegex(), " ")
        .trim()

    fun slug(value: String): String = normalize(value).replace(" ", "-").lowercase(Locale.ROOT).ifBlank { "usuario" }

    fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun deterministicId(prefix: String, source: String): String = "$prefix-${sha256Text(source).take(20)}"

    fun transactionCode(userName: String, date: String = TimeUtils.todayIso()): String {
        val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        val token = normalize(userName).replace(" ", "").take(10).ifBlank { "USUARIO" }
        val random = UUID.randomUUID().toString().replace("-", "").take(6).uppercase(Locale.ROOT)
        return "SKC-${d.format(DateTimeFormatter.BASIC_ISO_DATE)}-$token-$random"
    }

    fun messageCode(prefix: String, userName: String): String {
        val d = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val token = normalize(userName).replace(" ", "").take(8).ifBlank { "USER" }
        val random = UUID.randomUUID().toString().replace("-", "").take(6).uppercase(Locale.ROOT)
        return "$prefix-$d-$token-$random"
    }
}
