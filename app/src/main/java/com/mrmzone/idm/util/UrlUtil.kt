package com.mrmzone.idm.util

object UrlUtil {
    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    fun extractUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', ']') }
    fun extractAll(text: String?): List<String> = text?.let { t -> urlRegex.findAll(t).map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList() } ?: emptyList()
}
