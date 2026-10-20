package com.jarvis.assistant.data.memory

import android.util.Log
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MemoryManager(
    private val repository: MemoryRepository,
    private val preferences: AppPreferences,
    private val chatRepository: com.jarvis.assistant.data.repository.ChatRepository,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MemoryManager"
        private const val MAX_SHORT_TERM_TURNS = 25
    }

    // --- Short-Term Memory ---
    private val _shortTermHistory = MutableStateFlow<List<ChatTurn>>(emptyList())
    val shortTermHistory: StateFlow<List<ChatTurn>> = _shortTermHistory.asStateFlow()
    private val shortTermSummary = StringBuilder()

    fun addShortTermTurn(userText: String, jarvisText: String) {
        val current = _shortTermHistory.value.toMutableList()
        val turn = ChatTurn(
            userTranscript = userText.trim(),
            jarvisResponse = jarvisText.trim()
        )
        current.add(turn)

        if (current.size > MAX_SHORT_TERM_TURNS) {
            val pruned = current.removeAt(0)
            // Auto-summarize pruned turn into conversational summary context
            if (shortTermSummary.length < 500) {
                shortTermSummary.append("${pruned.userTranscript} -> ${pruned.jarvisResponse}; ")
            }
        }
        _shortTermHistory.value = current
    }

    fun clearShortTermMemory() {
        _shortTermHistory.value = emptyList()
        shortTermSummary.clear()
    }

    fun getShortTermContextSummary(): String {
        return shortTermSummary.toString().trim()
    }

    // --- Long-Term Memory ---

    suspend fun rememberFact(rawInput: String): String {
        val clean = rawInput
            .replace(Regex("(?i)^(hey jarvis|jarvis|please)?\\s*(remember that|remember to|remember)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return "I didn't catch what you'd like me to remember, sir."
        }

        // Also trigger automatic learning on the fact
        learnPreferencesFromConversation(clean)

        val lower = clean.lowercase(Locale.ROOT)
        val category = when {
            lower.contains("favorite") || lower.contains("prefer") || lower.contains("like") || lower.contains("song") || lower.contains("music") || lower.contains("app") ->
                MemoryEntity.CATEGORY_PREFERENCES
            lower.contains("routine") || lower.contains("gym") || lower.contains("work") || lower.contains("wake up") || lower.contains("sleep") || lower.contains("at ") || lower.contains("pm") || lower.contains("am") ->
                MemoryEntity.CATEGORY_ROUTINES
            lower.contains("name") || lower.contains("birthday") || lower.contains("live in") || lower.contains("age") || lower.contains("i am") || lower.contains("my ") ->
                MemoryEntity.CATEGORY_PERSONAL
            lower.contains("call") || lower.contains("contact") || lower.contains("phone") || lower.contains("number") || lower.contains("friend") || lower.contains("brother") || lower.contains("sister") || lower.contains("mom") || lower.contains("dad") ->
                MemoryEntity.CATEGORY_CONTACTS
            else ->
                MemoryEntity.CATEGORY_NOTES
        }

        val key = clean.take(40).trim()
        repository.saveMemory(
            rawText = clean,
            category = category,
            key = key,
            value = clean
        )
        Log.i(TAG, "Saved long-term memory in category '$category': $clean")
        val response = "I will remember that $clean."
        chatRepository.addTurn(clean, response)
        return response
    }

    suspend fun forgetFact(query: String): String {
        val clean = query
            .replace(Regex("(?i)^(hey jarvis|jarvis|please)?\\s*(forget that|forget my|forget about|forget)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return "Please tell me what you would like me to forget."
        }

        val deletedCount = repository.deleteMemoriesByKeyword(clean)
        val response = if (deletedCount > 0) {
            "I have erased $clean from my memory."
        } else {
            "I couldn't find any memory matching '$clean'."
        }
        chatRepository.addTurn(clean, response)
        return response
    }

    suspend fun getWhatDoYouRemember(): String {
        val all = repository.getAllMemories()
        if (all.isEmpty()) {
            return "My memory core is currently clear, sir. You can tell me things like 'My birthday is 15th August' or 'My favorite song is Believer'."
        }

        val count = all.size
        val topFacts = all.take(5).joinToString(". ") { it.rawText }
        val response = "I remember $count things about you in my Memory Core, including: $topFacts."
        chatRepository.addTurn("What do you remember about me?", response)
        return response
    }

    // --- Direct Recall Responses ---

    suspend fun getBirthdayResponse(): String {
        val bday = preferences.userBirthday
        if (bday.isNotBlank()) {
            val reply = "Sir, your birthday is $bday."
            chatRepository.addTurn("When is my birthday?", reply)
            return reply
        }
        val memories = repository.searchMemories("birthday")
        val mem = memories.firstOrNull()
        if (mem != null) {
            preferences.userBirthday = mem.value
            val reply = "Sir, your birthday is ${mem.value}."
            chatRepository.addTurn("When is my birthday?", reply)
            return reply
        }
        val notSet = "Sir, you haven't told me your birthday yet. You can tell me 'My birthday is 15th August' and I will remember it."
        chatRepository.addTurn("When is my birthday?", notSet)
        return notSet
    }

    fun getFavoriteSongResponse(): String {
        val song = resolveSongQuery("my favorite song")
        val reply = "Sir, your favorite song is $song."
        chatRepository.addTurn("What is my favorite song?", reply)
        return reply
    }

    suspend fun getRoutineResponse(): String {
        val allMemories = repository.getAllMemories()
        val routines = allMemories.filter { it.category == MemoryEntity.CATEGORY_ROUTINES }
        if (routines.isNotEmpty()) {
            val summary = routines.joinToString("; ") { it.rawText }
            val reply = "Sir, here is your recorded daily routine: $summary."
            chatRepository.addTurn("What is my routine?", reply)
            return reply
        }
        if (preferences.dailyRoutine.isNotBlank()) {
            val reply = "Sir, your daily routine is: ${preferences.dailyRoutine}."
            chatRepository.addTurn("What is my routine?", reply)
            return reply
        }
        val notSet = "Sir, you haven't recorded your routine yet. You can tell me 'I wake up at 7 am' or 'I go to gym at 6 pm' and I will save it."
        chatRepository.addTurn("What is my routine?", notSet)
        return notSet
    }

    // --- Automatic Habit & Preference Learning ---

    fun trackSongPlay(rawQuery: String) {
        val clean = rawQuery
            .replace(Regex("(?i)\\s+(song|video|gana|track|music|audio)$"), "")
            .replace(Regex("(?i)^(play|chalao|bajao)\\s+"), "")
            .trim()

        if (clean.isBlank() || clean.equals("my favorite song", ignoreCase = true) || clean.equals("favorite song", ignoreCase = true)) return

        val currentFav = preferences.recordSongPlay(clean)
        Log.i(TAG, "Tracked song play: '$clean'. Current top favorite: '$currentFav'")

        scope.launch(Dispatchers.IO) {
            repository.saveMemory(
                rawText = "User played and likes the song $clean",
                category = MemoryEntity.CATEGORY_PREFERENCES,
                key = "song_$clean",
                value = clean
            )
            if (currentFav.equals(clean, ignoreCase = true)) {
                repository.deleteMemoriesByKeyword("favorite_song")
                repository.saveMemory(
                    rawText = "User's favorite song is $clean",
                    category = MemoryEntity.CATEGORY_PREFERENCES,
                    key = "favorite_song",
                    value = clean
                )
            }
        }
    }

    fun resolveSongQuery(rawQuery: String): String {
        val clean = rawQuery.lowercase(Locale.ROOT).trim()
        val isFavoriteQuery = clean == "my favorite song" || clean == "my favourite song" ||
                clean == "favorite song" || clean == "favourite song" ||
                clean == "my song" || clean == "my favorite" || clean == "my favourite" ||
                clean == "something i like" || clean == "a song i like" ||
                clean == "mera favorite gana" || clean == "mera favourite gana" ||
                clean == "mera favorite song" || clean == "mera favourite song" ||
                clean == "mera pasandida gana" || clean == "pasandida gana"

        if (!isFavoriteQuery) {
            return rawQuery
        }

        // 1. Check AppPreferences favoriteSong
        val prefFav = preferences.favoriteSong
        if (prefFav.isNotBlank()) {
            Log.i(TAG, "Resolved 'my favorite song' from preferences: $prefFav")
            return prefFav
        }

        // 2. Check most played song from song counts
        val mostPlayed = preferences.getSongPlayCounts().maxByOrNull { it.value }?.key
        if (!mostPlayed.isNullOrBlank()) {
            Log.i(TAG, "Resolved 'my favorite song' from play count history: $mostPlayed")
            return mostPlayed
        }

        // 3. Fallback default
        Log.i(TAG, "No favorite song learned yet; defaulting to 'Tadipaar'")
        return "Tadipaar"
    }

    fun learnPreferencesFromConversation(userSpeech: String) {
        val clean = userSpeech.trim()
        if (clean.isBlank() || clean.length < 4) return

        scope.launch(Dispatchers.IO) {
            val lower = clean.lowercase(Locale.ROOT)

            // 1. BIRTHDAY EXTRACTION (English & Hinglish)
            val bdayMatch = Regex("(?i).*\\b(?:my\\s+birthday\\s+is|born\\s+on|my\\s+bday\\s+is)\\s+(?:on\\s+)?([a-zA-Z0-9\\s,]+?)(?:[.,!]|\$|\\s+sir|\\s+jarvis)").find(clean)
                ?: Regex("(?i).*\\b(?:mera\\s+)?(?:birthday|bday|janamdin)\\s+(?:hai\\s+)?([a-zA-Z0-9\\s]+?)(?:\\s+ko\\s+hai|\\s+ko|\\s+hai)?\$").find(clean)
                ?: Regex("(?i).*\\b([a-zA-Z0-9\\s]+?)\\s+(?:ko\\s+mera\\s+birthday\\s+hai|mera\\s+birthday\\s+hai).*").find(clean)
                ?: Regex("(?i).*\\b(?:i\\s+was\\s+born\\s+on)\\s+([a-zA-Z0-9\\s,]+?)[.,!]?\$").find(clean)

            if (bdayMatch != null) {
                var bday = bdayMatch.groupValues[1].trim()
                bday = bday.replace(Regex("(?i)^(on|ko|hai)\\s+"), "")
                    .replace(Regex("(?i)\\s+(ko|hai|sir|jarvis|please)$"), "")
                    .trim()
                if (bday.length in 3..40 && !listOf("when", "what", "today", "tomorrow").contains(bday.lowercase(Locale.ROOT))) {
                    preferences.userBirthday = bday
                    repository.deleteMemoriesByKeyword("birthday")
                    repository.saveMemory(
                        rawText = "User's birthday is $bday",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "birthday",
                        value = bday
                    )
                    chatRepository.addTurn(clean, "I have saved your birthday ($bday) to your Memory Core, sir.")
                    Log.i(TAG, "Learned user's birthday: $bday")
                }
            }

            // 2. AGE EXTRACTION
            val ageMatch = Regex("(?i).*\\b(?:i\\s+am|my\\s+age\\s+is|meri\\s+age|meri\\s+umar)\\s+(\\d{1,3})\\s*(?:years\\s+old|saal|sal|year\\s+old)?.*").find(clean)
            if (ageMatch != null) {
                val age = ageMatch.groupValues[1].trim()
                preferences.userAge = age
                repository.deleteMemoriesByKeyword("age")
                repository.saveMemory(
                    rawText = "User is $age years old",
                    category = MemoryEntity.CATEGORY_PERSONAL,
                    key = "age",
                    value = age
                )
                chatRepository.addTurn(clean, "I have noted that you are $age years old in your Memory Core, sir.")
                Log.i(TAG, "Learned user's age: $age")
            }

            // 3. LOCATION / RESIDENCE EXTRACTION
            val locMatch = Regex("(?i).*\\b(?:i\\s+live\\s+in|i\\s+stay\\s+in|i\\s+am\\s+from)\\s+([a-zA-Z\\s]+?)[.,!]?$").find(clean)
                ?: Regex("(?i).*\\bmain\\s+([a-zA-Z\\s]+?)\\s+mein\\s+rehta\\s+hoon.*").find(clean)
            if (locMatch != null) {
                val loc = locMatch.groupValues[1].trim()
                val nonLocs = listOf("here", "there", "home", "house", "city", "room", "india", "earth")
                if (loc.isNotBlank() && loc.length > 2 && !nonLocs.contains(loc.lowercase(Locale.ROOT))) {
                    preferences.userLocation = loc
                    repository.deleteMemoriesByKeyword("location")
                    repository.saveMemory(
                        rawText = "User lives in $loc",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "location",
                        value = loc
                    )
                    chatRepository.addTurn(clean, "I have noted that you live in $loc in your Memory Core, sir.")
                    Log.i(TAG, "Learned user's location: $loc")
                }
            }

            // 4. USER NAME
            val nameMatch = Regex("(?i).*\\b(?:my\\s+name\\s+is|call\\s+me|mera\\s+naam\\s+hai|mera\\s+naam)\\s+([a-zA-Z]+)[.,!]?$").find(clean)
            if (nameMatch != null) {
                val name = nameMatch.groupValues[1].trim()
                if (name.isNotBlank() && name.length > 1 && !listOf("jarvis", "sir", "boss", "user", "nothing", "what", "who").contains(name.lowercase(Locale.ROOT))) {
                    preferences.userName = name
                    repository.saveMemory(
                        rawText = "User's name is $name",
                        category = MemoryEntity.CATEGORY_PERSONAL,
                        key = "user_name",
                        value = name
                    )
                    chatRepository.addTurn(clean, "I will address you as $name, sir.")
                    Log.i(TAG, "Learned user's name: $name")
                }
            }

            // 5. DAILY ROUTINES (Wake Up, Sleep, Gym, Work, General)
            // A. Wake up
            val wakeUpMatch = Regex("(?i).*\\b(?:i\\s+wake\\s+up\\s+at|i\\s+get\\s+up\\s+at|my\\s+wake\\s+up\\s+time\\s+is)\\s+([0-9:apmAPM\\s]+?)(?:\\s+(?:in\\s+the\\s+morning|everyday|daily|am))?[.,!]?$").find(clean)
                ?: Regex("(?i).*\\bmain\\s+subah\\s+([0-9:apmAPM\\s]+?)(?:\\s+baje)?\\s+uthta\\s+hoon.*").find(clean)
            if (wakeUpMatch != null) {
                val time = wakeUpMatch.groupValues[1].trim()
                repository.deleteMemoriesByKeyword("wake up")
                repository.saveMemory(
                    rawText = "User wakes up at $time",
                    category = MemoryEntity.CATEGORY_ROUTINES,
                    key = "routine_wake_up",
                    value = time
                )
                chatRepository.addTurn(clean, "Noted in Routine: You wake up at $time.")
                Log.i(TAG, "Learned wake up routine: $time")
            }

            // B. Sleep
            val sleepMatch = Regex("(?i).*\\b(?:i\\s+sleep\\s+at|i\\s+go\\s+to\\s+bed\\s+at|i\\s+go\\s+to\\s+sleep\\s+at|my\\s+sleeping\\s+time\\s+is|bedtime\\s+is)\\s+([0-9:apmAPM\\s]+?)(?:\\s+(?:at\\s+night|everyday|daily|pm))?[.,!]?$").find(clean)
                ?: Regex("(?i).*\\bmain\\s+raat\\s+ko\\s+([0-9:apmAPM\\s]+?)(?:\\s+baje)?\\s+sota\\s+hoon.*").find(clean)
            if (sleepMatch != null) {
                val time = sleepMatch.groupValues[1].trim()
                repository.deleteMemoriesByKeyword("sleeps at")
                repository.saveMemory(
                    rawText = "User sleeps at $time",
                    category = MemoryEntity.CATEGORY_ROUTINES,
                    key = "routine_sleep",
                    value = time
                )
                chatRepository.addTurn(clean, "Noted in Routine: You sleep at $time.")
                Log.i(TAG, "Learned sleep routine: $time")
            }

            // C. Gym / Workout
            val gymMatch = Regex("(?i).*\\b(?:i\\s+go\\s+to\\s+gym\\s+at|gym\\s+time\\s+is|workout\\s+at)\\s+([0-9:apmAPM\\s]+?)(?:\\s+(?:everyday|daily|pm|am))?[.,!]?$").find(clean)
                ?: Regex("(?i).*\\bmain\\s+gym\\s+([0-9:apmAPM\\s]+?)(?:\\s+baje)?\\s+jata\\s+hoon.*").find(clean)
            if (gymMatch != null) {
                val time = gymMatch.groupValues[1].trim()
                repository.deleteMemoriesByKeyword("routine_gym")
                repository.saveMemory(
                    rawText = "User goes to gym at $time",
                    category = MemoryEntity.CATEGORY_ROUTINES,
                    key = "routine_gym",
                    value = time
                )
                chatRepository.addTurn(clean, "Noted in Routine: You go to gym at $time.")
                Log.i(TAG, "Learned gym routine: $time")
            }

            // D. Work / Office
            val workMatch = Regex("(?i).*\\b(?:i\\s+go\\s+to\\s+(?:office|work)\\s+at|(?:office|work)\\s+time\\s+is|work\\s+starts\\s+at)\\s+([0-9:apmAPM\\s]+?)(?:\\s+(?:daily|everyday|am))?[.,!]?$").find(clean)
            if (workMatch != null) {
                val time = workMatch.groupValues[1].trim()
                repository.deleteMemoriesByKeyword("routine_work")
                repository.saveMemory(
                    rawText = "User goes to work/office at $time",
                    category = MemoryEntity.CATEGORY_ROUTINES,
                    key = "routine_work",
                    value = time
                )
                chatRepository.addTurn(clean, "Noted in Routine: You go to office/work at $time.")
                Log.i(TAG, "Learned work routine: $time")
            }

            // E. General Routine
            val generalRoutineMatch = Regex("(?i).*\\b(?:my\\s+(?:daily\\s+)?routine\\s+is|mera\\s+(?:daily\\s+)?routine\\s+(?:hai|ye\\s+hai))\\s*[:\\-]?\\s*(.+)$").find(clean)
            if (generalRoutineMatch != null) {
                val routineText = generalRoutineMatch.groupValues[1].trim()
                if (routineText.isNotBlank() && routineText.length > 5) {
                    preferences.dailyRoutine = routineText
                    repository.deleteMemoriesByKeyword("Daily Routine:")
                    repository.saveMemory(
                        rawText = "Daily Routine: $routineText",
                        category = MemoryEntity.CATEGORY_ROUTINES,
                        key = "routine_daily",
                        value = routineText
                    )
                    chatRepository.addTurn(clean, "Noted in Routine: $routineText")
                    Log.i(TAG, "Learned general daily routine: $routineText")
                }
            }

            // 6. FAVORITE SONG
            val favSongMatch = Regex("(?i).*\\b(?:my\\s+favou?rite\\s+song\\s+is|i\\s+love\\s+(?:the\\s+)?song|i\\s+really\\s+like\\s+(?:the\\s+)?song)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:very\\s+much|a\\s+lot|too))?[.,!]?$").find(clean)
                ?: Regex("(?i).*\\b([a-zA-Z0-9\\s]+?)\\s+mera\\s+favou?rite\\s+(?:gana|song)\\s+hai.*").find(clean)
            if (favSongMatch != null) {
                val song = favSongMatch.groupValues[1].trim()
                    .replace(Regex("(?i)\\s+(song|gana|track)$"), "").trim()
                if (song.isNotBlank() && song.length > 2 && !listOf("it", "this", "that", "something").contains(song.lowercase(Locale.ROOT))) {
                    preferences.favoriteSong = song
                    repository.deleteMemoriesByKeyword("favorite_song")
                    repository.saveMemory(
                        rawText = "User's favorite song is $song",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "favorite_song",
                        value = song
                    )
                    chatRepository.addTurn(clean, "I have saved $song as your favorite song in your Memory Core, sir.")
                    Log.i(TAG, "Learned user's favorite song from conversation: $song")
                }
            }

            // 7. FAVORITE APP
            val favAppMatch = Regex("(?i).*\\b(?:my\\s+favou?rite\\s+app\\s+is|i\\s+prefer\\s+(?:using\\s+)?|i\\s+mostly\\s+use\\s+)([a-zA-Z0-9\\s]+?)(?:\\s+(?:app|for\\s+everything))?[.,!]?$").find(clean)
            if (favAppMatch != null) {
                val rawApp = favAppMatch.groupValues[1].trim()
                val app = rawApp.replace(Regex("(?i)\\s+(for\\s+.*|to\\s+.*|app)$"), "").trim()
                if (app.isNotBlank() && app.length > 2 && !listOf("it", "this", "that").contains(app.lowercase(Locale.ROOT))) {
                    preferences.favoriteApp = app
                    repository.deleteMemoriesByKeyword("favorite_app")
                    repository.saveMemory(
                        rawText = "User's favorite app is $app",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "favorite_app",
                        value = app
                    )
                    chatRepository.addTurn(clean, "I have saved $app as your favorite app in your Memory Core, sir.")
                    Log.i(TAG, "Learned user's favorite app from conversation: $app")
                }
            }

            // 8. FOOD PREFERENCES
            val foodMatch = Regex("(?i).*\\b(?:i\\s+love\\s+(?:eating\\s+)?|my\\s+favou?rite\\s+food\\s+is\\s+)([a-zA-Z0-9\\s]+?)[.,!]?$").find(clean)
            if (foodMatch != null) {
                val food = foodMatch.groupValues[1].trim()
                if (food.isNotBlank() && food.length > 2 && !listOf("you", "it", "this", "that", "music", "song").contains(food.lowercase(Locale.ROOT))) {
                    repository.saveMemory(
                        rawText = "Favorite food / likes eating $food",
                        category = MemoryEntity.CATEGORY_PREFERENCES,
                        key = "food_$food",
                        value = food
                    )
                    chatRepository.addTurn(clean, "Noted in Preferences: You like eating $food.")
                    Log.i(TAG, "Learned food preference from conversation: $food")
                }
            }

            // 9. PERSONAL RELATIONSHIPS / CONTACTS
            val contactMatch = Regex("(?i).*\\b([a-zA-Z0-9]+)\\s+is\\s+my\\s+(brother|sister|friend|best\\s+friend|mom|dad|father|mother|cousin|boss)[.,!]?$").find(clean)
            if (contactMatch != null) {
                val name = contactMatch.groupValues[1].trim()
                val relation = contactMatch.groupValues[2].trim()
                if (name.isNotBlank() && name.length > 1 && !listOf("who", "he", "she", "this", "that").contains(name.lowercase(Locale.ROOT))) {
                    repository.saveMemory(
                        rawText = "$name is user's $relation",
                        category = MemoryEntity.CATEGORY_CONTACTS,
                        key = "relation_$name",
                        value = relation
                    )
                    chatRepository.addTurn(clean, "Noted in Contacts: $name is your $relation.")
                    Log.i(TAG, "Learned personal contact relationship: $name ($relation)")
                }
            }
        }
    }

    suspend fun getSystemPromptContext(): String {
        val all = repository.getAllMemories()
        val favSong = resolveSongQuery("my favorite song")
        val sb = StringBuilder("\n[USER PERSONAL PROFILE & MEMORY CORE - HIGH PRIORITY]:\n")
        sb.append("- User's Preferred Name: ${preferences.userName.ifBlank { "Sir" }}\n")
        sb.append("- User's Birthday: ${preferences.userBirthday.ifBlank { "Not specified yet" }}\n")
        sb.append("- User's Known Favorite Song: $favSong\n")
        sb.append("- User's Favorite App: ${preferences.favoriteApp.ifBlank { "YouTube" }}\n")
        if (preferences.userLocation.isNotBlank()) {
            sb.append("- User's Home Location: ${preferences.userLocation}\n")
        }
        if (preferences.userAge.isNotBlank()) {
            sb.append("- User's Age: ${preferences.userAge} years old\n")
        }
        if (preferences.dailyRoutine.isNotBlank()) {
            sb.append("- User's Daily Routine: ${preferences.dailyRoutine}\n")
        }

        sb.append("\n[ALL SAVED MEMORIES & PREFERENCES IN CORE]:\n")
        all.take(30).forEach { mem ->
            sb.append("- [${mem.category}] ${mem.rawText}\n")
        }
        return sb.toString()
    }
}
