package dev.brahmkshatriya.echo.extension.spotify

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Encrypted fragmented-mp4 assembly for MV downloads (option B).
 *
 * The release downloader's contract (verified against its own build) is:
 * single-URL OkHttp GET -> PSSH parsed from mp4 boxes in that file ->
 * license -> single content key -> ffmpeg `-i <url> -decryption_key <key>
 * -c copy`. An MPD can never flow through it (no DASH awareness, no video
 * -map case, XML has no boxes). So for downloads we hand it ONE valid
 * multi-track encrypted fmp4: merged header + chronologically interleaved
 * media segments, served over loopback http.
 *
 * No crypto is touched anywhere here — only box structure (sizes, ids).
 * The bytes stay CENC-encrypted end to end; their CDM + ffmpeg flow does
 * the exact same decrypt it already does for audio.
 *
 * Shapes below were validated against real init bytes (merged init probes
 * clean: h264 1/1000000 + aac 1/48000, rewritten ids, deduped PSSH) and
 * synthetic moofs (tfhd/tfdt offsets). Conventions: video track = 1,
 * audio track = 2 (inits disagree internally — tkhd carries a bogus
 * shared id while trex says 1 — so both sides are rewritten explicit).
 */
object MvAssemble {

    const val VIDEO_ID = 1
    const val AUDIO_ID = 2

    data class Box(val type: String, val start: Int, val end: Int, val header: Int)

    /** Bounds-checked top/child box walk. Stops (doesn't throw) on junk. */
    fun children(buf: ByteArray, start: Int = 0, end: Int = buf.size): List<Box> {
        val out = mutableListOf<Box>()
        var o = start
        val lim = end.coerceAtMost(buf.size)
        while (o + 8 <= lim) {
            val size: Long
            val hdr: Int
            val s32 = buf.u32(o)
            if (s32 < 0) return out
            when (s32) {
                1L -> {
                    val big = buf.u64(o + 8)
                    if (big < 0) return out
                    size = big
                    hdr = 16
                }

                0L -> {
                    size = (lim - o).toLong()
                    hdr = 8
                }

                else -> {
                    size = s32
                    hdr = 8
                }
            }
            if (size < hdr || o + size > lim || size > Int.MAX_VALUE) return out
            val type = String(buf, o + 4, 4, Charsets.US_ASCII)
            out += Box(type, o, (o + size).toInt(), hdr)
            o += size.toInt()
        }
        return out
    }

    private fun ByteArray.u32(at: Int): Long {
        if (at < 0 || at + 4 > size) return -1
        return ((this[at].toLong() and 0xFF) shl 24) or
            ((this[at + 1].toLong() and 0xFF) shl 16) or
            ((this[at + 2].toLong() and 0xFF) shl 8) or
            (this[at + 3].toLong() and 0xFF)
    }

    private fun ByteArray.u64(at: Int): Long {
        if (at < 0 || at + 8 > size) return -1
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (this[at + i].toLong() and 0xFF)
        return v
    }

    /** Signed big-endian i32, null when out of bounds. */
    private fun ByteArray.i32(at: Int): Long? {
        if (at < 0 || at + 4 > size) return null
        return (((this[at].toInt() and 0xFF) shl 24) or
            ((this[at + 1].toInt() and 0xFF) shl 16) or
            ((this[at + 2].toInt() and 0xFF) shl 8) or
            (this[at + 3].toInt() and 0xFF)).toLong()
    }

    private fun ByteArray.patchU32(at: Int, v: Long): Boolean {
        if (at < 0 || at + 4 > size || v < 0 || v > 0xFFFFFFFFL) return false
        this[at] = (v shr 24).toByte()
        this[at + 1] = (v shr 16).toByte()
        this[at + 2] = (v shr 8).toByte()
        this[at + 3] = v.toByte()
        return true
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        val size = 8 + payload.size
        out[0] = (size shr 24).toByte()
        out[1] = (size shr 16).toByte()
        out[2] = (size shr 8).toByte()
        out[3] = size.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4, 0, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun List<Box>.first(type: String): Box? = firstOrNull { it.type == type }

    private val CONTAINERS = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf",
        "edts", "dinf", "sinf", "schi", "udta",
    )

    /**
     * Rebuild a box region minus [drop] types, sizes recomputed. stsd/meta
     * children start past their entry-count/version prefixes. Anything
     * unusual (largesize, unknown layout) is copied raw — removal only
     * happens on fully understood structure. Returns null when unchanged.
     */
    private fun stripRegion(
        buf: ByteArray, contentStart: Int, contentEnd: Int, drop: Set<String>,
    ): ByteArray? {
        var changed = false
        var out = ByteArray(0)
        for (b in children(buf, contentStart, contentEnd)) {
            if (b.type in drop) {
                changed = true
                continue
            }
            if (b.header != 8) {
                out += buf.copyOfRange(b.start, b.end)
                continue
            }
            val inner: ByteArray? = when {
                b.type == "stsd" && b.header == 8 ->
                    stripRegion(buf, b.start + b.header + 8, b.end, drop)
                        ?.let { kids ->
                            val head = boxHead(buf, b, 8)
                            head.patchU32(0, (16 + kids.size).toLong())
                            head + kids
                        }

                b.type == "meta" && b.header == 8 ->
                    stripRegion(buf, b.start + b.header + 4, b.end, drop)
                        ?.let { kids ->
                            val head = boxHead(buf, b, 4)
                            head.patchU32(0, (12 + kids.size).toLong())
                            head + kids
                        }

                b.type in CONTAINERS && b.header == 8 ->
                    stripRegion(buf, b.start + b.header, b.end, drop)
                        ?.let { box(b.type, it) }

                else -> null
            }
            if (inner != null) {
                changed = true
                out += inner
            } else {
                out += buf.copyOfRange(b.start, b.end)
            }
        }
        return if (changed) out else null
    }

    /** First [n] bytes of the box (size+type+prefix) for re-wrapping. */
    private fun boxHead(buf: ByteArray, b: Box, prefix: Int = 0): ByteArray {
        val n = b.header + prefix
        return buf.copyOfRange(b.start, b.start + n)
    }

    /** Rebuild the first top-level [type] box minus [drop]; original when
     * unchanged or on any structural doubt. */
    private fun stripBox(buf: ByteArray, type: String, drop: Set<String>): ByteArray {
        val top = children(buf).firstOrNull { it.type == type } ?: return buf
        if (top.header != 8) return buf
        val inner = stripRegion(buf, top.start + top.header, top.end, drop) ?: return buf
        return box(type, inner)
    }

    /**
     * Merged header (ftyp + moov) from a video + audio init pair.
     * Returns null on any structural surprise (caller fails the download
     * cleanly instead of emitting a corrupt file).
     */
    fun mergeHeader(videoInit: ByteArray, audioInit: ByteArray): ByteArray? = runCatching {
        val vTop = children(videoInit)
        val aTop = children(audioInit)
        val vMoov = vTop.first("moov") ?: return@runCatching null
        val aMoov = aTop.first("moov") ?: return@runCatching null
        val vCh = children(videoInit, vMoov.start + vMoov.header, vMoov.end)
        val aCh = children(audioInit, aMoov.start + aMoov.header, aMoov.end)

        // ftyp: video major + union of compatible brands.
        val vFtyp = vTop.first("ftyp") ?: return@runCatching null
        val aFtyp = aTop.first("ftyp") ?: return@runCatching null
        val major = videoInit.copyOfRange(vFtyp.start + vFtyp.header, vFtyp.start + vFtyp.header + 4)
        val brands = mutableListOf<ByteArray>()
        fun collect(box: Box, buf: ByteArray) {
            var i = box.start + box.header + 8
            while (i + 4 <= box.end) {
                val b = buf.copyOfRange(i, i + 4)
                if (brands.none { it.contentEquals(b) }) brands += b
                i += 4
            }
        }
        collect(vFtyp, videoInit)
        collect(aFtyp, audioInit)
        val ftypPayload = major + ByteArray(4) + brands.fold(ByteArray(0)) { acc, b -> acc + b }
        val ftyp = box("ftyp", ftypPayload)

        // mvhd: video's, next_track_ID=3, duration=max(both, video timescale).
        // v0 offsets (v1 shifts creation/modification to 64-bit).
        val vMvhd = vCh.first("mvhd") ?: return@runCatching null
        val aMvhd = aCh.first("mvhd")
        val mvhd = videoInit.copyOfRange(vMvhd.start, vMvhd.end)
        mvhd.patchU32(mvhd.size - 4, 3L)
        runCatching {
            if (mvhd[8].toInt() != 0) return@runCatching
            val vTs = mvhd.u32(8 + 12)
            val vDur = mvhd.u32(8 + 16)
            val aOff = aMvhd?.let { it.start + it.header } ?: -1
            val aTs = if (aOff >= 0 && audioInit.getOrNull(aOff) == 0.toByte()) {
                audioInit.u32(aOff + 12)
            } else -1
            val aDur = if (aOff >= 0 && audioInit.getOrNull(aOff) == 0.toByte()) {
                audioInit.u32(aOff + 16)
            } else -1
            if (vTs > 0 && vDur >= 0 && aTs > 0 && aDur >= 0) {
                val maxUs = maxOf(vDur * 1_000_000L / vTs, aDur * 1_000_000L / aTs)
                mvhd.patchU32(8 + 16, maxUs * vTs / 1_000_000L)
            }
        }

        // traks: video -> 1, audio -> 2 (tkhd v0 id at content+8).
        val vTrak = vCh.first("trak") ?: return@runCatching null
        val aTrak = aCh.first("trak") ?: return@runCatching null
        val vTrakB = videoInit.copyOfRange(vTrak.start, vTrak.end)
        val aTrakB = audioInit.copyOfRange(aTrak.start, aTrak.end)
        fun patchTkhd(trak: ByteArray, id: Long): Boolean {
            val tk = children(trak, 8, trak.size).first("tkhd") ?: return false
            val ver = trak.getOrNull(tk.start + tk.header)?.toInt() ?: return false
            if (ver != 0) return false
            return trak.patchU32(tk.start + tk.header + 8, id)
        }
        if (!patchTkhd(vTrakB, VIDEO_ID.toLong())) return@runCatching null
        if (!patchTkhd(aTrakB, AUDIO_ID.toLong())) return@runCatching null
        // Audio-only grouping descriptions (sgpd) go here: older demuxers
        // can fail the whole track parsing them while the sgpd-free video
        // sails through. Sizes cascade via the moov rebuild below.
        val aTrakS = stripBox(aTrakB, "trak", setOf("sgpd"))

        // mvex: video mehd (if present) + video trex(id 1) + audio trex(->2).
        val (vTrex, vMehd) = runCatching {
            val ch = children(videoInit, vMoov.start + vMoov.header, vMoov.end)
            val mvex = ch.first("mvex") ?: return@runCatching null to null
            val mch = children(videoInit, mvex.start + mvex.header, mvex.end)
            val tx = mch.first("trex") ?: return@runCatching null to null
            val b = videoInit.copyOfRange(tx.start, tx.end)
            if (!b.patchU32(tx.header + 4, VIDEO_ID.toLong())) return@runCatching null to null
            b to mch.first("mehd")?.let { videoInit.copyOfRange(it.start, it.end) }
        }.getOrNull() ?: (null to null)
        if (vTrex == null) return@runCatching null
        val aTrex = runCatching {
            val ch = children(audioInit, aMoov.start + aMoov.header, aMoov.end)
            val mvex = ch.first("mvex") ?: return@runCatching null
            val mch = children(audioInit, mvex.start + mvex.header, mvex.end)
            val tx = mch.first("trex") ?: return@runCatching null
            val b = audioInit.copyOfRange(tx.start, tx.end)
            if (!b.patchU32(tx.header + 4, AUDIO_ID.toLong())) return@runCatching null
            b
        }.getOrNull() ?: return@runCatching null
        val mvex = box("mvex", (vMehd ?: ByteArray(0)) + vTrex + aTrex)

        // pssh: video copies (identical across tracks — verified byte-equal).
        val pssh = vCh.filter { it.type == "pssh" }
            .map { videoInit.copyOfRange(it.start, it.end) }
            .takeIf { it.isNotEmpty() } ?: return@runCatching null
        val meta = vCh.first("meta")?.let { videoInit.copyOfRange(it.start, it.end) }
            ?: ByteArray(0)

        val moovPayload = mvhd + meta + vTrakB + aTrakS + mvex + pssh.fold(ByteArray(0)) { a, b -> a + b }
        box("moov", moovPayload).let { ftyp + it }
    }.getOrNull()

    /**
     * Rewrite every traf/tfhd track id in a segment to [newId] (tfhd id
     * sits at content+4 regardless of flags). Returns the original bytes
     * untouched when no moof/traf/tfhd is found (video segments pass
     * through natively at id 1).
     */
    fun patchTfhd(segment: ByteArray, newId: Long): ByteArray {
        val out = segment.copyOf()
        var touched = false
        for (moof in children(out)) {
            if (moof.type != "moof") continue
            for (traf in children(out, moof.start + moof.header, moof.end)) {
                if (traf.type != "traf") continue
                for (tfhd in children(out, traf.start + traf.header, traf.end)) {
                    if (tfhd.type != "tfhd") continue
                    if (out.patchU32(tfhd.start + tfhd.header + 4, newId)) touched = true
                }
            }
        }
        return if (touched) out else segment
    }

    /**
     * Strip top-level index boxes (sidx/ssix) from a segment. Their byte
     * ranges point at the ORIGINAL per-segment layout and go stale the
     * moment segments concatenate — ffmpeg honors them for seeking and
     * then mis-reads (or drops) the track, while linear readers are fine.
     * That asymmetry (video clean, audio gone) is exactly the observed
     * failure when only one track's segments carry an index. moof/mdat/
     * styp/emsg pass through untouched.
     */
    fun stripSidx(segment: ByteArray): ByteArray {
        var saw = false
        for (b in children(segment)) {
            if (b.type == "sidx" || b.type == "ssix") {
                saw = true
                break
            }
        }
        if (!saw) return segment
        var out = ByteArray(0)
        for (b in children(segment)) {
            if (b.type != "sidx" && b.type != "ssix") {
                out += segment.copyOfRange(b.start, b.end)
            }
        }
        return out
    }

    /** True when the bytes hold at least one moof (real media, not an
     * error page served with 200). */
    fun hasMoof(segment: ByteArray): Boolean =
        children(segment).any { it.type == "moof" }

    /**
     * Normalize audio fragment aux info to senc-inline form.
     *
     * Video fragments carry per-sample IVs inline (senc) which every
     * toolchain reads. Audio fragments may instead reference sidecar
     * aux blobs (saiz/saio), which older demuxers can't follow — the
     * track then decrypts nowhere and vanishes downstream while video
     * survives. This rehousing moves each sample's IV into a senc box
     * (same bytes, same key, same samples), drops the now-dead
     * saiz/saio, and fixes trun data offsets + box sizes accordingly.
     *
     * Strictly structural (no crypto): IVs are non-secret nonces copied
     * verbatim. Returns the ORIGINAL bytes on any surprise (senc already
     * present, missing boxes, count mismatch, out-of-bounds aux) — never
     * throws, never emits a corrupt segment.
     */
    fun normalizeAux(segment: ByteArray, log: (String) -> Unit = {}): ByteArray =
        runCatching { normalizeAuxInner(segment, log) }.getOrDefault(segment)

    private fun normalizeAuxInner(segment: ByteArray, log: (String) -> Unit): ByteArray {
        val rebuilt = ByteArrayOutputStream()
        var any = false
        for (top in children(segment)) {
            if (top.type != "moof") {
                rebuilt.write(segment, top.start, top.end - top.start)
                continue
            }
            val rebuiltMoof = convertMoof(segment, top, log) ?: run {
                rebuilt.write(segment, top.start, top.end - top.start)
                continue
            }
            any = true
            rebuilt.write(rebuiltMoof, 0, rebuiltMoof.size)
        }
        return if (any) rebuilt.toByteArray() else segment
    }

    private fun convertMoof(segment: ByteArray, moof: Box, log: (String) -> Unit): ByteArray? {
        if (moof.header != 8) return null
        val parts = mutableListOf<ByteArray>()
        var changed = false
        for (child in children(segment, moof.start + moof.header, moof.end)) {
            if (child.type != "traf" || child.header != 8) {
                parts += segment.copyOfRange(child.start, child.end)
                continue
            }
            val rebuilt = convertTraf(segment, moof.start, child, log) ?: run {
                parts += segment.copyOfRange(child.start, child.end)
                continue
            }
            changed = true
            parts += rebuilt
        }
        if (!changed) return null
        var payload = ByteArray(0)
        for (p in parts) payload += p
        return box("moof", payload)
    }

    /**
     * Rebuild one traf: sample-grouping refs (sbgp) always stripped (older
     * demuxers choke on them while the working track carries none), and
     * sidecar aux (saiz/saio) converted to senc-inline when parseable.
     * Null = nothing to change (pass through).
     */
    private fun convertTraf(
        segment: ByteArray, moofStart: Int, traf: Box, log: (String) -> Unit,
    ): ByteArray? {
        if (traf.header != 8) return null
        val kids = children(segment, traf.start + traf.header, traf.end)
        val byType = kids.associateBy { it.type }
        val tfhd = byType["tfhd"]
        val flags = tfhd?.let { segment.u32(it.start + it.header) } ?: -1
        // Explicit absolute base-data-offset poisons every moof-relative
        // fixup below unless it already equals this moof: pass through.
        if (tfhd != null && flags and 0x1L != 0L) {
            if (segment.getOrNull(tfhd.start + tfhd.header)?.toInt() != 0) {
                log("skip:tfhd-ver")
                return null
            }
            val explicit = segment.u32(tfhd.start + tfhd.header + 8)
            if (explicit != moofStart.toLong()) {
                log("skip:tfhd-base")
                return null
            }
        }
        // Try senc conversion when needed and possible.
        var senc: ByteArray? = null
        val saiz = byType["saiz"]
        val saio = byType["saio"]
        val converting = !byType.containsKey("senc") && saiz != null && saio != null &&
            tfhd != null && saiz.header == 8 && saio.header == 8
        if (converting) {
            senc = buildSenc(segment, moofStart, kids, saiz!!, saio!!, flags, log)
            if (senc == null) {
                // Unparseable aux: still strip the grouping refs below,
                // but keep saiz/saio (today's behavior) rather than fail.
                log("skip:aux-shape")
            }
        }
        // Dropped set: grouping refs always; aux boxes only when converted.
        val drop = mutableSetOf("sbgp")
        if (senc != null) {
            drop += "saiz"
            drop += "saio"
        }
        val dropped = kids.filter { it.type in drop }
        if (dropped.isEmpty() && senc == null) return null
        val removed = dropped.sumOf { (it.end - it.start).toLong() }
        // Net moof shrink: dropped boxes leave, appended senc stays inside
        // the moof (before mdat), pushing samples back out by its size.
        // Forgetting +senc misaligns every audio sample by ~1KB: track
        // present but silent while video sails through. EchoDown is
        // logless, so this must be exact here — no downstream signal.
        val added = senc?.size?.toLong() ?: 0L
        val net = removed - added
        var rebuilt = ByteArray(0)
        for (k in kids) {
            if (k.type in drop) continue
            if (k.type == "trun" && net != 0L) {
                val tb = segment.copyOfRange(k.start, k.end)
                val fl = segment.u32(k.start + k.header)
                // data_offset exists iff flags&1, at content+8, SIGNED.
                if (fl and 0x1L != 0L) {
                    val cur = segment.i32(k.start + k.header + 8) ?: return null
                    val adj = cur - net
                    if (adj < Int.MIN_VALUE || adj > Int.MAX_VALUE) return null
                    if (!tb.patchU32(k.header + 8, adj and 0xFFFFFFFFL)) return null
                }
                rebuilt += tb
            } else {
                rebuilt += segment.copyOfRange(k.start, k.end)
            }
        }
        if (senc != null) rebuilt += senc
        return box("traf", rebuilt)
    }

    /** Build a senc box from saiz/saio sidecar aux. Null = unparseable. */
    private fun buildSenc(
        segment: ByteArray, moofStart: Int, kids: List<Box>,
        saiz: Box, saio: Box, flags: Long, log: (String) -> Unit,
    ): ByteArray? {
        // saiz: ver0, flags&1 (defSize+count), aux_info_type=="cenc".
        if (segment.getOrNull(saiz.start + saiz.header)?.toInt() != 0) {
            log("skip:saiz-ver")
            return null
        }
        if (segment.u32(saiz.start + saiz.header) != 1L) {
            log("skip:saiz-flags")
            return null
        }
        // Aux entry proof: default_sample_info_size is the entry size for
        // EVERY sample. Bare IVs (no subsample tail) means it equals the
        // IV size exactly — equality PROVES it, since tails would make
        // entries bigger. default 0 = per-sample sizes array: require each
        // entry == 8 or all == 16. Else abort to passthrough.
        var p = saiz.start + saiz.header + 4
        if (segment.copyOfRange(p, p + 4).toString(Charsets.US_ASCII) != "cenc") {
            log("skip:saiz-type")
            return null
        }
        p += 8
        val firstSize = segment.getOrNull(p)?.toInt() ?: run {
            log("skip:saiz-oob")
            return null
        }
        val ivSize: Int
        val count: Long
        if (firstSize == 0) {
            var sp = p + 5
            val uniform = segment.getOrNull(sp)?.toInt() ?: run {
                log("skip:saiz-oob")
                return null
            }
            if (uniform != 8 && uniform != 16) {
                log("skip:saiz-ivsize")
                return null
            }
            ivSize = uniform
            count = segment.u32(p + 1)
            if (count <= 0 || count > 100_000) {
                log("skip:saiz-count")
                return null
            }
            sp = p + 5
            repeat(count.toInt()) {
                if (segment.getOrNull(sp)?.toInt() != uniform) {
                    log("skip:saiz-mixed")
                    return null
                }
                sp += 1
            }
        } else {
            if (firstSize != 8 && firstSize != 16) {
                log("skip:saiz-ivsize")
                return null
            }
            ivSize = firstSize
            count = segment.u32(p + 1)
            if (count <= 0 || count > 100_000) {
                log("skip:saiz-count")
                return null
            }
        }
        // trun sample total must agree.
        var total = 0L
        for (k in kids) {
            if (k.type != "trun") continue
            val n = segment.u32(k.start + k.header + 4)
            if (n < 0) {
                log("skip:trun-oob")
                return null
            }
            total += n
        }
        if (total != count) {
            log("skip:count-mismatch")
            return null
        }
        // saio: ver0/1, "cenc", param, entry_count, offsets.
        val saioVer = segment.getOrNull(saio.start + saio.header)?.toInt() ?: run {
            log("skip:saio-oob")
            return null
        }
        if (saioVer != 0 && saioVer != 1) {
            log("skip:saio-ver")
            return null
        }
        var q = saio.start + saio.header + 4
        if (segment.copyOfRange(q, q + 4).toString(Charsets.US_ASCII) != "cenc") {
            log("skip:saio-type")
            return null
        }
        q += 8
        val entries = segment.u32(q)
        if (entries <= 0 || entries > 64) {
            log("skip:saio-entries")
            return null
        }
        q += 4
        val step = if (saioVer == 1) 8 else 4
        val offs = LongArray(entries.toInt()) { i ->
            if (step == 8) segment.u64(q + i * 8) else segment.u32(q + i * 4)
        }
        if (offs.any { it < 0 }) {
            log("skip:saio-oob")
            return null
        }
        var base = moofStart.toLong()
        if (flags and 0x1L != 0L) {
            // Explicit absolute base (v0 32-bit): must equal this moof.
            val tfhdBox = kids.firstOrNull { it.type == "tfhd" } ?: run {
                log("skip:tfhd-missing")
                return null
            }
            if (segment.getOrNull(tfhdBox.start + tfhdBox.header)?.toInt() != 0) {
                log("skip:tfhd-ver")
                return null
            }
            val explicit = segment.u32(tfhdBox.start + tfhdBox.header + 8)
            if (explicit != moofStart.toLong()) {
                log("skip:tfhd-base")
                return null
            }
            base = explicit
        }
        // Multi-blob aux concatenates ONLY under contiguity proof: blob i
        // spans [offs[i], offs[i+1]) and the last spans the exact remainder
        // of totalBytes. Gaps/overlaps/trailing junk abort to passthrough.
        val totalBytes = count * ivSize
        val aux: ByteArray
        if (entries == 1L) {
            val start = base + offs[0]
            if (start < 0 || start + totalBytes > segment.size) {
                log("skip:aux-range")
                return null
            }
            aux = segment.copyOfRange(start.toInt(), (start + totalBytes).toInt())
        } else {
            val lens = LongArray(offs.size)
            var sum = 0L
            for (i in offs.indices) {
                val start = base + offs[i]
                if (start < 0 || start > segment.size) {
                    log("skip:aux-range")
                    return null
                }
                lens[i] = if (i + 1 < offs.size) {
                    val next = base + offs[i + 1]
                    if (next <= start) {
                        log("skip:aux-order")
                        return null
                    }
                    next - start
                } else {
                    totalBytes - sum
                }
                if (lens[i] <= 0 || start + lens[i] > segment.size) {
                    log("skip:aux-range")
                    return null
                }
                sum += lens[i]
            }
            if (sum != totalBytes) {
                log("skip:aux-size")
                return null
            }
            var blob = ByteArray(0)
            for (i in offs.indices) {
                val start = base + offs[i]
                blob += segment.copyOfRange(start.toInt(), (start + lens[i]).toInt())
            }
            aux = blob
        }
        var sencPayload = ByteArray(4 + 4)
        sencPayload.patchU32(4, count)
        for (i in 0 until count.toInt()) {
            sencPayload += aux.copyOfRange(i * ivSize, (i + 1) * ivSize)
            sencPayload += byteArrayOf(0, 0)
        }
        return box("senc", sencPayload)
    }

    /**
     * One-line aux layout description for the first audio moof found
     * (traf-level vs moof-level aux boxes). Decides everything downstream.
     */
    fun auxShape(segment: ByteArray): String {
        for (top in children(segment)) {
            if (top.type != "moof") continue
            val mkids = children(segment, top.start + top.header, top.end)
                .map { it.type }.toSet()
            val trafs = mkids.count { it == "traf" }
            val tset = mutableSetOf<String>()
            for (traf in children(segment, top.start + top.header, top.end)) {
                if (traf.type != "traf") continue
                for (k in children(segment, traf.start + traf.header, traf.end)) {
                    if (k.type in setOf("senc", "saiz", "saio", "sbgp", "tfhd", "trun")) {
                        tset += "traf:${k.type}"
                    }
                }
                break
            }
            val mset = mkids.filter { it in setOf("saiz", "saio", "senc", "sbgp", "tfhd", "trun") }
                .map { "moof:$it" }
            return "trafs=$trafs ${(tset + mset).sorted().joinToString(",")}"
        }
        return "no-moof"
    }
    fun sampleStats(segment: ByteArray): Triple<Int, Long, Long> {
        var moofs = 0
        var samples = 0L
        var mdat = 0L
        for (top in children(segment)) {
            if (top.type == "moof") {
                moofs++
                for (traf in children(segment, top.start + top.header, top.end)) {
                    if (traf.type != "traf") continue
                    for (trun in children(segment, traf.start + traf.header, traf.end)) {
                        if (trun.type != "trun") continue
                        // trun content: version+flags(4), then sample_count(4).
                        val n = segment.u32(trun.start + trun.header + 4)
                        if (n > 0) samples += n
                    }
                }
            }
            if (top.type == "mdat") mdat += (top.end - top.start - top.header).toLong()
        }
        return Triple(moofs, samples, mdat)
    }
    fun moofTime(segment: ByteArray, timescale: Long): Long? {
        if (timescale <= 0) return null
        for (moof in children(segment)) {
            if (moof.type != "moof") continue
            for (traf in children(segment, moof.start + moof.header, moof.end)) {
                if (traf.type != "traf") continue
                for (tfdt in children(segment, traf.start + traf.header, traf.end)) {
                    if (tfdt.type != "tfdt") continue
                    val ver = segment.getOrNull(tfdt.start + tfdt.header)?.toInt() ?: return null
                    val base = if (ver == 1) segment.u64(tfdt.start + tfdt.header + 4)
                    else segment.u32(tfdt.start + tfdt.header + 4)
                    if (base < 0) return null
                    return base * 1000L / timescale
                }
            }
        }
        return null
    }

    /**
     * Assemble one multi-track encrypted fmp4: [header] then per-index
     * video+audio segment pairs streamed to [out]. Returns video/audio
     * byte counts. Segments pass through untouched except audio tfhd
     * (rewritten to [AUDIO_ID]).
     */
    fun interleave(
        out: OutputStream,
        header: ByteArray,
        videoSegs: List<ByteArray>,
        audioSegs: List<ByteArray>,
    ): Pair<Long, Long> {
        out.write(header)
        var v = header.size.toLong()
        var a = 0L
        val n = maxOf(videoSegs.size, audioSegs.size)
        for (i in 0 until n) {
            videoSegs.getOrNull(i)?.let {
                out.write(it)
                v += it.size
            }
            audioSegs.getOrNull(i)?.let {
                val patched = patchTfhd(it, AUDIO_ID.toLong())
                out.write(patched)
                a += patched.size
            }
        }
        out.flush()
        return v to a
    }
}
