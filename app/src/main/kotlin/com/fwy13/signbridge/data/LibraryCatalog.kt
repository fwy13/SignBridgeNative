package com.fwy13.signbridge.data

import com.fwy13.signbridge.BuildConfig
import android.net.Uri
import java.text.Normalizer
import java.text.Collator
import java.util.Locale

data class LibraryEntry(val id: String, val word: String, val videoUrl: String?)
fun normalize(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("[\\u0300-\\u036f]"), "").replace('đ', 'd')
fun signVideo(id: String?): String? = id?.takeIf { it.isNotBlank() }?.let { "https://res.cloudinary.com/dwdsrtyim/video/upload/q_auto,f_auto/v1/$it.mp4" }
object LibraryCatalog {
    val vocabulary: List<LibraryEntry> by lazy {
        val collator = Collator.getInstance(Locale("vi"))
        listOf(Vocabulary000(),Vocabulary001(),Vocabulary002(),Vocabulary003(),Vocabulary004(),Vocabulary005(),Vocabulary006(),Vocabulary007(),Vocabulary008(),Vocabulary009(),Vocabulary010(),Vocabulary011(),Vocabulary012(),Vocabulary013(),Vocabulary014(),Vocabulary015(),Vocabulary016(),Vocabulary017(),Vocabulary018(),Vocabulary019(),Vocabulary020(),Vocabulary021(),Vocabulary022(),Vocabulary023(),Vocabulary024(),Vocabulary025(),Vocabulary026(),Vocabulary027(),Vocabulary028(),Vocabulary029(),Vocabulary030(),Vocabulary031(),Vocabulary032(),Vocabulary033(),Vocabulary034(),Vocabulary035(),Vocabulary036(),Vocabulary037(),Vocabulary038(),Vocabulary039(),Vocabulary040(),Vocabulary041(),Vocabulary042(),Vocabulary043(),Vocabulary044(),Vocabulary045(),Vocabulary046(),Vocabulary047(),Vocabulary048(),Vocabulary049(),Vocabulary050(),Vocabulary051(),Vocabulary052(),Vocabulary053(),Vocabulary054()).flatten().sortedWith { a, b -> collator.compare(a.word, b.word) }
    }
    val alphabet: List<LibraryEntry> by lazy { listOf(
        LibraryEntry("alphabet-A", "A", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("A_exvtai")}.mp4"),
        LibraryEntry("alphabet-Ă", "Ă", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Ă_zgp71s")}.mp4"),
        LibraryEntry("alphabet-Â", "Â", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Â_nevrjf")}.mp4"),
        LibraryEntry("alphabet-B", "B", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("B_wqrvx1")}.mp4"),
        LibraryEntry("alphabet-C", "C", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("C_yyrzps")}.mp4"),
        LibraryEntry("alphabet-D", "D", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("D_olxhgj")}.mp4"),
        LibraryEntry("alphabet-Đ", "Đ", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Đ_sv2iie")}.mp4"),
        LibraryEntry("alphabet-E", "E", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("E_ij4xkh")}.mp4"),
        LibraryEntry("alphabet-Ê", "Ê", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Ê_l9kzgh")}.mp4"),
        LibraryEntry("alphabet-G", "G", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("G_ur7lfg")}.mp4"),
        LibraryEntry("alphabet-H", "H", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("H_sttqmt")}.mp4"),
        LibraryEntry("alphabet-I", "I", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("I_k6aegq")}.mp4"),
        LibraryEntry("alphabet-K", "K", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("K_cd5ibt")}.mp4"),
        LibraryEntry("alphabet-L", "L", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("L_ezccgr")}.mp4"),
        LibraryEntry("alphabet-M", "M", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("M_f4jpmp")}.mp4"),
        LibraryEntry("alphabet-N", "N", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("N_iwzew7")}.mp4"),
        LibraryEntry("alphabet-O", "O", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("O_qwgitp")}.mp4"),
        LibraryEntry("alphabet-Ô", "Ô", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Ô_qjd5h1")}.mp4"),
        LibraryEntry("alphabet-Ơ", "Ơ", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Ơ_maxxhx")}.mp4"),
        LibraryEntry("alphabet-P", "P", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("P_z4go0f")}.mp4"),
        LibraryEntry("alphabet-Q", "Q", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Q_ed4emt")}.mp4"),
        LibraryEntry("alphabet-R", "R", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("R_lzbnza")}.mp4"),
        LibraryEntry("alphabet-S", "S", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("S_r5doi4")}.mp4"),
        LibraryEntry("alphabet-T", "T", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("T_z6tdp7")}.mp4"),
        LibraryEntry("alphabet-U", "U", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("U_nwwkno")}.mp4"),
        LibraryEntry("alphabet-Ư", "Ư", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Ư_cxro50")}.mp4"),
        LibraryEntry("alphabet-V", "V", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("V_ds2vsq")}.mp4"),
        LibraryEntry("alphabet-X", "X", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("X_r8wijy")}.mp4"),
        LibraryEntry("alphabet-Y", "Y", "https://res.cloudinary.com/${Uri.encode(BuildConfig.ALPHABET_CLOUD)}/video/upload/f_mp4,vc_h264/q_auto/${Uri.encode("Y_i7v5ex")}.mp4"),
    ) }
    fun region(id: String) = when(id.lastOrNull()?.uppercaseChar()) { 'B' -> "Bắc"; 'N' -> "Nam"; 'T' -> "Trung"; else -> "Chung" }
    fun filter(alphabetMode: Boolean, query: String, region: String, descending: Boolean): List<LibraryEntry> {
        val q = if (alphabetMode) Normalizer.normalize(query.trim(), Normalizer.Form.NFC).lowercase() else normalize(query)
        val source = if (alphabetMode) alphabet else vocabulary
        val result = source.filter {
            val word = if (alphabetMode) it.word.lowercase() else normalize(it.word)
            word.contains(q) && (alphabetMode || region == "Tất cả" || LibraryCatalog.region(it.id) == region)
        }
        return if (descending) result.reversed() else result
    }
}
