package com.fwy13.signbridge.ui

import java.util.Locale

fun conciseError(
    raw: String,
    english: Boolean = false,
    fallbackVi: String = "Không thể thực hiện. Vui lòng thử lại.",
    fallbackEn: String = "Unable to continue. Please try again."
): String {
    val value = raw.lowercase(Locale.ROOT)

    fun text(vi: String, en: String) = if (english) en else vi

    return when {
        value.isBlank() -> ""

        "permission" in value ||
                "securityexception" in value ->
            text("Chưa có quyền truy cập.", "Access permission is required.")

        "sha-256" in value ||
                "checksum" in value ->
            text(
                "Dữ liệu model không khớp. Hãy tải lại.",
                "Model data does not match. Download it again."
            )

        "hand_landmarker.task" in value &&
                (
                        "import" in value ||
                                "not found" in value ||
                                "missing" in value ||
                                "no such file" in value
                        ) ->
            text(
                "Thiếu bộ nhận diện bàn tay.",
                "Hand detector is missing."
            )

        "unknown mode model" in value ||
                "unknown default model" in value ->
            text(
                "Chưa có cấu hình model.",
                "Model configuration is missing."
            )

        "import " in value ||
                "filenotfound" in value ||
                "no such file" in value ||
                "model not found" in value ||
                "missing model" in value ->
            text(
                "Không tìm thấy model.",
                "Model not found."
            )

        "signature" in value ||
                "shape mismatch" in value ||
                "tensor" in value ||
                "preprocessing" in value ||
                "unsupported runtime" in value ||
                "expected fp32" in value ->
            text(
                "Model không tương thích.",
                "This model is incompatible."
            )

        "timeout" in value ||
                "timed out" in value ->
            text(
                "Kết nối quá lâu. Hãy thử lại.",
                "Connection timed out. Try again."
            )

        "unknownhost" in value ||
                "unable to resolve host" in value ||
                "failed to connect" in value ->
            text(
                "Không thể kết nối mạng.",
                "Unable to connect."
            )

        "camera" in value ->
            text(
                "Không thể mở camera.",
                "Unable to open the camera."
            )

        else -> text(fallbackVi, fallbackEn)
    }
}