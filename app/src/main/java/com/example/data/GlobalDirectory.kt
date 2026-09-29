package com.example.data

data class GlobalUser(
    val username: String,
    val displayName: String,
    val country: String,
    val countryCode: String,
    val countryFlag: String,
    val language: String,
    val nativeGreeting: String,
    val bio: String,
    val avatarEmoji: String,
    val region: String,
    val autoReplies: List<String>
)

object GlobalDirectory {
    val REGIONS = listOf("All Regions", "Asia & Pacific", "Americas", "Europe", "Africa & Middle East")

    val GLOBAL_USERS = listOf(
        GlobalUser(
            username = "yuki_jp",
            displayName = "Yuki Tanaka",
            country = "Japan",
            countryCode = "JP",
            countryFlag = "🇯🇵",
            language = "Japanese",
            nativeGreeting = "こんにちは！お元気ですか？",
            bio = "Minimalist designer & open privacy advocate from Tokyo 🗾",
            avatarEmoji = "🌸",
            region = "Asia & Pacific",
            autoReplies = listOf(
                "こんにちは！(Hello!) It's great to connect on this secure encrypted network!",
                "In Tokyo right now. How is the connection on your end? The low-data mode works so smoothly here.",
                "Thank you for reaching out! End-to-end encryption gives such peace of mind."
            )
        ),
        GlobalUser(
            username = "mateo_br",
            displayName = "Mateo Silva",
            country = "Brazil",
            countryCode = "BR",
            countryFlag = "🇧🇷",
            language = "Portuguese",
            nativeGreeting = "Olá! Como você está hoje?",
            bio = "Visual storyteller & travel photographer from São Paulo 📸",
            avatarEmoji = "🌿",
            region = "Americas",
            autoReplies = listOf(
                "Olá amigo! Glad to connect across the ocean!",
                "I just took some high-res shots of the Atlantic forest. Sending compressed via data-saver so you can preview instantly!",
                "Obrigado! Always happy to meet secure messaging friends globally."
            )
        ),
        GlobalUser(
            username = "elena_de",
            displayName = "Elena Rostova",
            country = "Germany",
            countryCode = "DE",
            countryFlag = "🇩🇪",
            language = "German",
            nativeGreeting = "Hallo! Schön, dich hier zu treffen.",
            bio = "Privacy researcher & cryptographer in Berlin 🛡️",
            avatarEmoji = "⚡",
            region = "Europe",
            autoReplies = listOf(
                "Guten Tag! Verified our AES-256 session key fingerprint. All looks mathematically sound!",
                "No phone numbers, zero tracking—this is how all modern messaging should be built.",
                "Greetings from Berlin! Hope your encrypted sync is running smoothly."
            )
        ),
        GlobalUser(
            username = "priya_in",
            displayName = "Priya Sharma",
            country = "India",
            countryCode = "IN",
            countryFlag = "🇮🇳",
            language = "Hindi",
            nativeGreeting = "नमस्ते! आप कैसे हैं?",
            bio = "Open-source developer building decentralized systems in Bengaluru 🇮🇳",
            avatarEmoji = "✨",
            region = "Asia & Pacific",
            autoReplies = listOf(
                "Namaste! The low-bandwidth mode is fantastic for rural and mobile connections here.",
                "Sending high-res media without ads or bloat feels so liberating!",
                "Delighted to connect! Let me know if you need any translation help with Hindi."
            )
        ),
        GlobalUser(
            username = "lucas_ca",
            displayName = "Lucas Tremblay",
            country = "Canada",
            countryCode = "CA",
            countryFlag = "🇨🇦",
            language = "French",
            nativeGreeting = "Bonjour! Ravi de faire ta connaissance.",
            bio = "Alpine photographer and climber from Montreal 🏔️",
            avatarEmoji = "🍁",
            region = "Americas",
            autoReplies = listOf(
                "Bonjour! Hiking up Mount Royal right now with crisp autumn air.",
                "Sent a high-res landscape photograph through the encrypted channel. Enjoy the view!",
                "Cross-platform sync caught up to my desktop instantly. Super slick!"
            )
        ),
        GlobalUser(
            username = "amina_sn",
            displayName = "Amina Diallo",
            country = "Senegal",
            countryCode = "SN",
            countryFlag = "🇸🇳",
            language = "French / Wolof",
            nativeGreeting = "Nanga def! Jàmm nga am.",
            bio = "Climate data scientist & education mentor in Dakar 🌍",
            avatarEmoji = "☀️",
            region = "Africa & Middle East",
            autoReplies = listOf(
                "Jàmm nga am! (Peace be upon you!) Welcome to our global conversation circle.",
                "Having an accessible app that requires less mobile data is a game-changer for our university students here.",
                "Thank you for the message! Sending warm greetings from Dakar."
            )
        ),
        GlobalUser(
            username = "aarav_np",
            displayName = "Aarav Joshi",
            country = "Nepal",
            countryCode = "NP",
            countryFlag = "🇳🇵",
            language = "Nepali",
            nativeGreeting = "नमस्ते! हिमालबाट शुभकामना।",
            bio = "Community builder & tech enthusiast in Kathmandu 🏔️",
            avatarEmoji = "🕊️",
            region = "Asia & Pacific",
            autoReplies = listOf(
                "Namaste! Warm regards from the foothills of the Himalayas!",
                "Even on 2G edge connectivity in mountain valleys, Guff sends messages instantly.",
                "Dhanyabad (Thank you)! Looking forward to keeping in touch across borders."
            )
        ),
        GlobalUser(
            username = "jihoon_kr",
            displayName = "Ji-hoon Park",
            country = "South Korea",
            countryCode = "KR",
            countryFlag = "🇰🇷",
            language = "Korean",
            nativeGreeting = "안녕하세요! 반갑습니다.",
            bio = "Sound engineer and AI researcher in Seoul 🎧",
            avatarEmoji = "🌙",
            region = "Asia & Pacific",
            autoReplies = listOf(
                "안녕하세요! (Hello!) Just checked the end-to-end security key. Perfectly verified.",
                "The dark theme looks gorgeous on OLED displays here in nighttime Seoul.",
                "Have you tried asking Gemini for instant translations in the chat? It's remarkably fast!"
            )
        )
    )
}
