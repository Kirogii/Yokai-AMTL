package eu.kanade.tachiyomi.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Try to guess if the backup is an old aniyomi backup.
 *
 * Returns true if it's (probably) an old aniyomi backup, or false if it's a mihon backup
 * or a new aniyomi backup.
 */
object BackupDetector {
    @Serializable
    data class BackupDetector(
        @ProtoNumber(103) val backupAnimeSources: List<DetectAnimeSource> = emptyList(),
        @ProtoNumber(500) val isLegacy: Boolean = true,
    ) {
        @Serializable
        data class DetectAnimeSource(
            @ProtoNumber(1) val name: String = "",
            @ProtoNumber(2) val sourceId: Long,
        )
    }

    fun isLegacyBackup(bytes: ByteArray): Boolean {
        return try {
            val detect = ProtoBuf.decodeFromByteArray(BackupDetector.serializer(), bytes)
            detect.isLegacy && detect.backupAnimeSources.isNotEmpty()
        } catch (_: SerializationException) {
            false
        }
    }

    /**
     * Positively identify a Mihon / Tachiyomi(-derived) backup (as opposed to a
     * native Aniyomi/Tadami backup).
     *
     * Native Aniyomi/Tadami backups always carry at least one "native marker" field
     * at the top level: backupAnimeCategories(4), backupNovel(5),
     * backupNovelCategories(6), or any field >= 500 (BackupCreator always writes
     * isLegacy=false at field 500, and all anime/novel/feature data lives in the
     * 500+ range). Mihon backups only ever use field numbers {1,2,100,101,104,105,106}.
     *
     * So a backup with no native marker but recognizable Mihon content is a Mihon
     * backup. Must be checked AFTER [isLegacyBackup].
     */
    fun isMihonBackup(bytes: ByteArray): Boolean {
        val fields = try {
            topLevelFieldNumbers(bytes)
        } catch (_: Exception) {
            return false
        }
        val hasNativeMarker = fields.any { it == 4 || it == 5 || it == 6 || it >= 500 }
        val hasMihonContent = fields.any { it in MIHON_CONTENT_FIELDS }
        return !hasNativeMarker && hasMihonContent
    }

    private val MIHON_CONTENT_FIELDS = setOf(1, 2, 101, 104, 105, 106)

    /**
     * Walk the top level of a protobuf message and collect the field numbers present.
     * Nested messages are skipped wholesale (not recursed into).
     */
    private fun topLevelFieldNumbers(bytes: ByteArray): Set<Int> {
        val fields = mutableSetOf<Int>()
        var pos = 0
        while (pos < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, pos)
            pos = afterTag
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            if (fieldNumber == 0) throw SerializationException("Invalid protobuf field number 0")
            fields += fieldNumber
            pos = when (wireType) {
                0 -> readVarint(bytes, pos).second // varint
                1 -> pos + 8 // 64-bit
                2 -> { // length-delimited
                    val (len, afterLen) = readVarint(bytes, pos)
                    afterLen + len.toInt()
                }
                5 -> pos + 4 // 32-bit
                else -> throw SerializationException("Unsupported protobuf wire type $wireType")
            }
            if (pos > bytes.size) throw SerializationException("Truncated protobuf message")
        }
        return fields
    }

    /** Reads a base-128 varint. Returns (value, indexAfterVarint). */
    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            result = result or ((b.toLong() and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift >= 64) throw SerializationException("Varint too long")
        }
        throw SerializationException("Truncated varint")
    }
}
