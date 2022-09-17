package net.adriantodt.sneakypng

import java.awt.image.BufferedImage
import java.io.File
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import javax.imageio.ImageIO
import kotlin.experimental.and
import kotlin.experimental.or

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Main: enc | dec" }
    when (args[0]) {
        "enc" -> {
            require(args.size == 4) { "Encoding: <origin> <data> <output>" }
            val origin = File(args[1])
            val data = File(args[2])
            val output = File(args[3])
            encodeBit(origin, data, output)
        }
        "dec" -> {
            require(args.size == 4) { "Decoding: <origin> <data> <output>" }
            val origin = File(args[1])
            val modified = File(args[2])
            val output = File(args[3])
            decodeBit(origin, modified, output)
        }
        "debug" -> {
            require(args.size == 2) { "Decoding: <origin>" }
            val max = ImageIO.read(File(args[1])).maxDataLength
            println("Max data length for origin file: ${humanReadableByteCountBin(max)} / ${humanReadableByteCountSI(max)}")
        }
    }
}

val BufferedImage.maxDataLength
    get() = (this.width * this.height * 3L) / 8 - 4

val masks = byteArrayOf(-128, 64, 32, 16, 8, 4, 2, 1)

private operator fun <A, B> Sequence<A>.times(that: Sequence<B>) = flatMap { x -> that.map { y -> x to y } }

@JvmName("timesPair")
private operator fun <A, B, C> Sequence<A>.times(that: Sequence<Pair<B, C>>) = flatMap { x ->
    that.map { (y, z) -> Triple(x, y, z) }
}

enum class PrimaryColor(private val encodeMask: Int, private val decodeMask: Int) {
    R(0x010000, 0xff0000),
    G(0x000100, 0x00ff00),
    B(0x000001, 0x0000ff);

    fun encodeBit(rgb: Int): Int {
        return rgb xor encodeMask
    }

    fun decodeBit(rgb1: Int, rgb2: Int): Boolean {
        return rgb1 and decodeMask != rgb2 and decodeMask
    }
}

fun <T> seq(iterable: Iterable<T>) = iterable.asSequence()

fun <T> seq(array: Array<T>) = array.asSequence()

fun encodeBit(origin: File, data: File, output: File) {
    require(output.extension == "png") { "Output must be a PNG file." }
    val originImage = ImageIO.read(origin)
    val binData = data.readBytes()
    val max = originImage.maxDataLength
    require(binData.size <= max) {
        "The origin image can only handle ${humanReadableByteCountBin(max)} / ${humanReadableByteCountSI(max)}. Please use less data or a bigger origin image."
    }

    val bitSeq = (intToByteArray(binData.size) + binData).asSequence().flatMap { masks.map { m -> (it and m) == m } }
    val coordinates = seq(0 until originImage.width) * seq(0 until originImage.height)
    val imageRange = seq(PrimaryColor.values()) * coordinates

    val operations = bitSeq.zip(imageRange) { a, b -> if (a) b else null }.filterNotNull()

    for ((primaryColor, x, y) in operations) {
        originImage.setRGB(x, y, primaryColor.encodeBit(originImage.getRGB(x, y)))
    }

    ImageIO.write(originImage, "png", output)
}

fun decodeBit(origin: File, data: File, output: File) {
    val originImage = ImageIO.read(origin)
    val dataImage = ImageIO.read(data)

    val coordinates = seq(0 until originImage.width) * seq(0 until originImage.height)
    val imageRange = seq(PrimaryColor.values()) * coordinates

    val byteSeq = imageRange
        .map { (primaryColor, x, y) -> primaryColor.decodeBit(originImage.getRGB(x, y), dataImage.getRGB(x, y)) }
        .chunked(8) { masks.zip(it) { a, b -> if (b) a else 0 }.reduce { a, b -> a or b } }

    val decodedData =
        byteSeq.drop(4).take(byteArrayToInt(byteSeq.take(4).toList().toByteArray())).toList().toByteArray()

    output.writeBytes(decodedData)
}

fun intToByteArray(value: Int): ByteArray {
    return byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )
}

fun byteArrayToInt(bytes: ByteArray): Int {
    val (b1, b2, b3, b4) = bytes

    return 0xFF and b1.toInt() shl 24 or
        (0xFF and b2.toInt() shl 16) or
        (0xFF and b3.toInt() shl 8) or
        (0xFF and b4.toInt())
}

fun humanReadableByteCountSI(bytes: Long): String? {
    var bytes = bytes
    if (-1000 < bytes && bytes < 1000) {
        return "$bytes B"
    }
    val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
    while (bytes <= -999950 || bytes >= 999950) {
        bytes /= 1000
        ci.next()
    }
    return String.format("%.1f %cB", bytes / 1000.0, ci.current())
}

fun humanReadableByteCountBin(bytes: Long): String? {
    val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(bytes)
    if (absB < 1024) {
        return "$bytes B"
    }
    var value = absB
    val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
    var i = 40
    while (i >= 0 && absB > 0xfffccccccccccccL shr i) {
        value = value shr 10
        ci.next()
        i -= 10
    }
    value *= java.lang.Long.signum(bytes).toLong()
    return String.format("%.1f %ciB", value / 1024.0, ci.current())
}
