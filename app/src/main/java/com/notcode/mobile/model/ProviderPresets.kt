package com.notcode.mobile.model

data class ProviderPreset(val name: String, val baseUrl: String, val suggestedModel: String, val note: String = "")

object ProviderPresets {
    val items = listOf(
        ProviderPreset("OpenAI", "https://api.openai.com/v1", "", "Укажите ID доступной вам модели OpenAI."),
        ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        ProviderPreset("Claude", "https://api.anthropic.com/v1", "", "OpenAI-совместимый интерфейс Anthropic. Укажите ID модели Claude; совместимость tools зависит от модели."),
        ProviderPreset("Qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen-plus", "Международный endpoint Alibaba Cloud; регион должен совпадать с API-ключом."),
        ProviderPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "", "OpenAI-совместимый интерфейс Google. Укажите доступный ID Gemini."),
        ProviderPreset("Groq", "https://api.groq.com/openai/v1", ""),
        ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", "", "Укажите полный ID модели из каталога OpenRouter."),
        ProviderPreset("Свой провайдер", "", "", "Любой HTTPS OpenAI-совместимый API."),
    )
}
