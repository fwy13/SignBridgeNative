package com.fwy13.signbridge.network

import com.fwy13.signbridge.BuildConfig
import com.fwy13.signbridge.data.Pronouns
import com.fwy13.signbridge.data.normalize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class SentenceOptions(val pronouns: Pronouns = Pronouns(), val dialect: String? = null, val dictionary: Map<String,String> = emptyMap())
class SentenceBuilder(private val http: Http) {
    private val vocabulary = mapOf(
        "hoa-de" to listOf("rang muối", "tôm", "lột da cá", "lột vỏ tôm", "lấy chỉ tôm", "cắt kỳ", "đánh vẩy cá", "cắt đầu cá"),
        "can-tho" to listOf("nhân viên", "máy khoan", "khoan", "đồ đạc", "cà phê", "chào cờ", "phục vụ", "vào lớp"),
        "spa" to listOf("nặn mụn", "úp móng"), "trung" to listOf("Miến Điện"),
        "bang-chu-cai" to "A B C D E G H I K L M N O P Q R S T U V X Y Z".split(' '))
    private val dialectLabels = mapOf("hoa-de" to "Nghề cá / Hòa Đê", "can-tho" to "Cần Thơ phổ thông", "spa" to "Nghề spa", "trung" to "Địa danh", "bang-chu-cai" to "Bảng chữ cái")
    suspend fun build(raw: List<String>, options: SentenceOptions): String {
        currentCoroutineContext().ensureActive()
        val tokens=raw.map(String::trim).filter(String::isNotEmpty)
        if(tokens.isEmpty())return ""
        val first=clean(call(tokens,options))
        if(acceptable(first,tokens))return first
        val corrected=clean(call(tokens,options,first))
        currentCoroutineContext().ensureActive()
        require(acceptable(corrected,tokens)) { "LLM could not produce an acceptable Vietnamese sentence" }
        return corrected
    }
    private fun words(text: String)=normalize(text).replace(Regex("[^a-z0-9\\s]")," ").split(Regex("\\s+")).filter(String::isNotBlank)
    private fun missing(sentence: String,tokens: List<String>): List<String> {
        val normalized=normalize(sentence); val set=words(sentence).toSet()
        return tokens.filter { !normalized.contains(normalize(it)) && (words(it).isEmpty() || !words(it).all(set::contains)) }
    }
    private fun echo(sentence: String,tokens: List<String>)=tokens.size>=2 && words(sentence)==tokens.flatMap(::words)
    private fun acceptable(sentence: String,tokens: List<String>)=sentence.isNotBlank() && missing(sentence,tokens).isEmpty() && !echo(sentence,tokens) && words(sentence).size<=tokens.flatMap(::words).size+10
    private fun clean(text: String)=text.trim().replace(Regex("^(?:câu hoàn chỉnh|kết quả|output)\\s*:\\s*",RegexOption.IGNORE_CASE),"").replace(Regex("^[\"“]|[\"”]$"),"").trim()
    private fun system(options: SentenceOptions): String {
        val p=options.pronouns
        val subject=if(p.addSubject) """Cách xưng hô do người dùng chọn:
  - Người đang ký tự xưng là "${p.speaker}".
  - Gọi người nghe là "${p.listener}".
  Khi câu nói về người đang ký và thiếu chủ ngữ, được thêm "${p.speaker}".
  Khi là câu hỏi hoặc yêu cầu hướng tới người nghe và thiếu chủ ngữ, được thêm "${p.listener}"."""
        else "Người dùng chọn không tự động thêm chủ ngữ. Không thêm đại từ nếu input không có chủ ngữ."
        val examples=if(p.addSubject) """- ["đi", "chợ", "mua", "cá"] với người đang ký là "${p.speaker}" → ${p.speaker.replaceFirstChar { it.uppercase() }} đi chợ mua cá.
  - ["cà phê", "uống", "không"] với người nghe là "${p.listener}" → ${p.listener.replaceFirstChar { it.uppercase() }} có uống cà phê không?""" else """- ["đi", "chợ", "mua", "cá"] → Đi chợ mua cá."""
        val dialect=vocabulary[options.dialect]?.let { "\nNgười dùng thuộc nhóm \"${dialectLabels[options.dialect] ?: options.dialect}\".\nCác từ chuyên ngành hay gặp: ${it.joinToString(", ")}." }.orEmpty()
        val dictionary=if(options.dictionary.isEmpty()) "" else "\nTừ điển riêng của người dùng:\n"+options.dictionary.entries.joinToString("\n") { "- \"${it.key}\" nghĩa là \"${it.value}\"" }+"\nƯu tiên dùng từ điển này khi phù hợp. Nếu có tên riêng, hãy dùng tên riêng thay vì từ chung."
        return """Bạn là biên dịch viên chuyển các đơn vị ý nghĩa từ ngôn ngữ ký hiệu thành đúng một câu tiếng Việt tự nhiên.
  Input là danh sách từ/cụm từ mang ý nghĩa, không phải thứ tự từ bắt buộc.

  Quy tắc:
  1. Bảo toàn đầy đủ ý nghĩa của mọi từ/cụm từ trong input, nhưng được sắp xếp lại trật tự.
  2. Được thêm từ ngữ pháp tối thiểu như có, đã, đang, sẽ, bị, được, một, các và dấu câu để câu tự nhiên.
  3. Nếu input đã có chủ ngữ hoặc một danh từ rõ ràng làm chủ thể, phải ưu tiên chủ ngữ đó và không thay bằng đại từ mặc định.
  4. Không thêm người, đồ vật, hành động, địa điểm, thời gian hoặc sự kiện không có trong input.
  5. Không giải thích, không dùng lời mở đầu, không đặt câu trong dấu ngoặc kép. Chỉ trả về đúng một câu.

  $subject

  Ví dụ:
  - ["cơm", "tôi", "ăn"] → Tôi ăn cơm.
  - ["máy khoan", "hỏng"] → Máy khoan bị hỏng.
  $examples
  $dialect$dictionary"""
    }
    private suspend fun call(tokens: List<String>,options: SentenceOptions,previous: String?=null): String {
        var prompt="Các từ nhận diện được: ${JSONArray(tokens)}"
        if(previous!=null) prompt+=if(echo(previous,tokens)) """
Câu trước chỉ lặp lại nguyên thứ tự ký hiệu: ${JSONObject.quote(previous)}.
Đây không phải câu tiếng Việt tự nhiên. Bắt buộc xác định chủ ngữ, vị ngữ và bổ ngữ; sắp xếp lại theo ngữ pháp tiếng Việt.
Ví dụ: ["tôi", "cơm", "ăn"] phải viết thành "Tôi ăn cơm.", không được trả về "tôi cơm ăn".""" else """
Câu trước chưa bảo toàn các ý: ${JSONArray(missing(previous,tokens))}.
Câu trước: ${JSONObject.quote(previous)}.
Hãy sửa lại thành đúng một câu tự nhiên, có đủ các ý còn thiếu và không thêm thông tin mới."""
        return when(BuildConfig.LLM_PROVIDER) {
            "groq" -> {
                require(BuildConfig.GROQ_API_KEY.isNotBlank()) { "GROQ_API_KEY is not configured" }
                val messages=JSONArray().put(JSONObject().put("role","system").put("content",system(options))).put(JSONObject().put("role","user").put("content",prompt))
                val response=JSONObject(http.text("https://api.groq.com/openai/v1/chat/completions","POST",
                    JSONObject().put("model",BuildConfig.GROQ_MODEL).put("max_tokens",100).put("temperature",.3).put("messages",messages),
                    mapOf("Authorization" to "Bearer ${BuildConfig.GROQ_API_KEY}")))
                response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
            "ollama" -> {
                require(BuildConfig.OLLAMA_BASE_URL.isNotBlank()) { "OLLAMA_BASE_URL is not configured" }
                withTimeout(30_000) {
                    val response=JSONObject(http.text(BuildConfig.OLLAMA_BASE_URL.trimEnd('/')+"/api/generate","POST",
                        JSONObject().put("model",BuildConfig.OLLAMA_MODEL).put("system",system(options)).put("prompt",prompt).put("stream",false)
                            .put("options",JSONObject().put("temperature",.1).put("num_predict",60))))
                    response.getString("response").trim()
                }
            }
            else -> error("Unsupported LLM provider: ${BuildConfig.LLM_PROVIDER}")
        }
    }
}
