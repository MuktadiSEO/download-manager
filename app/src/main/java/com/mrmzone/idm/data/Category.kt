package com.mrmzone.idm.data

enum class Category(val label: String, val folder: String, val exts: Set<String>) {
    VIDEO("Video", "Video", setOf("mp4","mkv","avi","mov","wmv","flv","webm","3gp","m4v","mpg","mpeg","ts","vob","ogv","rm","rmvb")),
    MUSIC("Music", "Music", setOf("mp3","m4a","aac","wav","flac","ogg","wma","opus","amr","mid","midi","aif","aiff","ra")),
    DOCUMENTS("Documents", "Documents", setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","rtf","odt","ods","odp","epub","csv","pps","mobi")),
    COMPRESSED("Compressed", "Compressed", setOf("zip","rar","7z","tar","gz","bz2","xz","tgz","iso","img","arj","lzh","sit","sitx","z","ace","r00","r01")),
    PROGRAMS("Programs", "Programs", setOf("apk","xapk","apks","exe","msi","dmg","deb","rpm","bin","jar","appx","msu")),
    IMAGES("Images", "Images", setOf("jpg","jpeg","png","gif","webp","bmp","svg","ico","tif","tiff","heic")),
    OTHER("General", "General", emptySet());

    companion object {
        fun fromFileName(name: String): Category {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.exts } ?: OTHER
        }
        val downloadableExts: Set<String> by lazy { entries.flatMap { it.exts }.toSet() - setOf("htm", "html") }
    }
}
