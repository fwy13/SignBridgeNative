package com.fwy13.signbridge.data

object ModelContent {
    fun text(name: String): String? = when(name) {
        "bigru_attention_alphabet_20260818_114440_colab_524c028a_labels.json" -> ModelContent00()
        "bigru_attention_alphabet_20260818_114440_display_labels.json" -> ModelContent01()
        "bigru_attention_alphabet_20260818_114440_verified_labels.json" -> ModelContent02()
        "bigru_attention_alphabet_20260914_092530_executorch_1dca17c6.metadata.json" -> ModelContent03()
        "bigru_attention_alphabet_20260914_092530_executorch_1dca17c6_labels.json" -> ModelContent04()
        "handgcn_alphabet_20260913_175720_colab_f780742c_labels.json" -> ModelContent05()
        "handgcn_lite_dialect-bang-chu-cai_20261019_223626.metadata.json" -> ModelContent06()
        "handgcn_lite_dialect-bang-chu-cai_20261019_223626_labels.json" -> ModelContent07()
        "model.json" -> ModelContent08()
        "model_bigru_dc868da1_labels.json" -> ModelContent09()
        "tcn_20260624_232642_display_labels.json" -> ModelContent10()
        "tcn_20260721_115258_display_labels.json" -> ModelContent11()
        "tcn_20260814_104243_display_labels.json" -> ModelContent12()
        "tcn_alphabet_20260912_121104_display_labels.json" -> ModelContent13()
        "tcn_dialect-hoa-de_20260721_160609_display_labels.json" -> ModelContent14()
        "tflite_models.json" -> ModelContent15()
        "voya_tcn_alphabet_30class_fp32_d7a6bee0_labels.json" -> ModelContent16()
        else -> null
    }
}
