package io.github.dbyoder.transcribe.core

/**
 * Turns the names in your contacts into words for the speech model to listen out for: first,
 * middle and last names, one word each. Leaves out titles, family labels ("Mom"), business words
 * ("Pizza", "Dentist") and names that are also everyday words ("Will", "Grace", "May"): listening
 * out for those would bend ordinary speech towards names, and the model knows them anyway.
 */
object ContactNameFilter {
    const val MAX_NAMES = 2000

    private val SEPARATORS = Regex("""[\s,;/&+|()\[\]{}"“”.:!?@#*~_=<>0-9]+""")

    /** @param names name fields from your contacts, most important first (e.g. starred). */
    fun words(names: List<String>, max: Int = MAX_NAMES): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (name in names) {
            for (raw in name.replace('’', '\'').split(SEPARATORS)) {
                val word = raw.trim('\'', '-')
                if (!looksLikeName(word)) continue
                val cased = tidyCase(word)
                if (seen.add(cased.lowercase())) {
                    out += cased
                    if (out.size >= max) return out
                }
            }
        }
        return out
    }

    private fun looksLikeName(word: String): Boolean =
        word.length >= 2 &&
            word.first().isLetter() &&
            word.all { it.isLetter() || it == '\'' || it == '-' } &&
            word.lowercase() !in NOT_NAMES

    /** "john" and "JOHN" → "John". "McDonald", "DeShawn" and short initials like "JD" stay as they are. */
    private fun tidyCase(word: String): String {
        val letters = word.filter { it.isLetter() }
        val allLower = letters.all { it.isLowerCase() }
        val allUpper = letters.all { it.isUpperCase() }
        return when {
            allLower || (allUpper && letters.length > 3) -> word.lowercase().replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

    private val NOT_NAMES = setOf(
        // Titles and suffixes.
        "mr", "mrs", "ms", "miss", "mx", "dr", "doctor", "prof", "professor", "sir", "madam", "rev",
        "fr", "jr", "sr", "ii", "iii", "iv", "md", "dds", "phd", "esq", "cpa", "rn",
        // Family and other labels people give contacts.
        "mom", "mum", "mommy", "mama", "dad", "daddy", "papa", "mother", "father", "sister", "sis",
        "brother", "bro", "aunt", "auntie", "aunty", "uncle", "cousin", "grandma", "grandpa",
        "granny", "grandmother", "grandfather", "nana", "nan", "gran", "wife", "husband", "hubby",
        "son", "daughter", "baby", "babe", "honey", "hun", "love", "boss", "work", "home", "office",
        "cell", "mobile", "main", "landline", "fax", "pager", "other", "personal", "business",
        "friend", "neighbor", "neighbour", "coach", "teacher", "nurse", "landlord", "roommate",
        "ex", "me", "my", "myself",
        // Businesses and services.
        "the", "and", "of", "for", "to", "at", "in", "on", "by", "with", "from", "an", "or", "de",
        "la", "company", "co", "inc", "llc", "ltd", "corp", "corporation", "group", "team", "dept",
        "department", "support", "service", "services", "customer", "care", "help", "helpline",
        "info", "information", "center", "centre", "store", "shop", "market", "restaurant", "pizza",
        "cafe", "coffee", "bar", "grill", "kitchen", "bakery", "deli", "bank", "insurance",
        "school", "college", "university", "church", "hospital", "clinic", "medical", "health",
        "dentist", "dental", "pharmacy", "vet", "hotel", "taxi", "cab", "delivery", "pickup",
        "emergency", "voicemail", "number", "phone", "contact", "unknown", "private", "new", "old",
        "salon", "spa", "gym", "fitness", "auto", "repair", "plumber", "electric", "electrician",
        "garage", "cleaners", "cleaning", "realty", "law", "legal", "attorney", "lawyer", "agent",
        "account", "accounts", "billing", "sales", "reception", "front", "desk", "pool", "club",
        "city", "county", "state", "police", "fire", "post", "mail",
        // Names that are also everyday words.
        "will", "bill", "mark", "grace", "hope", "joy", "may", "june", "april", "august", "rose",
        "faith", "chase", "hunter", "summer", "autumn", "dawn", "sandy", "rich", "frank", "art",
        "guy", "pat", "sue", "rob", "jack", "max", "ray", "sky", "river", "storm", "gene", "lane",
        "page", "penny", "ruby", "amber", "crystal", "iris", "ivy", "lily", "daisy", "holly",
        "heather", "olive", "pearl", "violet", "jade", "sage", "cash", "king", "young", "white",
        "black", "brown", "green", "gray", "grey", "long", "little", "small", "hill", "wood",
        "woods", "stone", "field", "fields", "park", "bell", "cook", "baker", "miller", "bush",
        "rice", "price", "hart", "hall", "wall", "ward", "west", "north", "south", "east", "best",
        "bright", "banks", "burns", "cross", "day", "drew", "fair", "ford", "fox", "frost", "glass",
        "gold", "golden", "good", "grant", "hale", "hay", "hunt", "justice", "kind", "knight",
        "lamb", "light", "lord", "low", "moon", "more", "noble", "paris", "pope", "power", "rain",
        "reed", "ring", "rivers", "rock", "rush", "sharp", "short", "silver", "snow", "spring",
        "still", "strong", "swift", "temple", "trust", "wise", "wolf", "worth", "major", "chance",
        "colt", "dash", "duke", "earl", "ace", "angel", "star", "sunny", "honor", "honour",
        "liberty", "harmony", "melody", "destiny", "trinity", "serenity", "journey", "blue", "red",
        "rusty", "buddy", "sonny", "sunshine", "bliss", "mercy", "patience", "prudence", "charity",
        "constance", "royal", "prince", "princess", "queen", "bishop", "marshal", "shepherd",
        "brook", "cliff", "clay", "flint", "forest", "ridge", "rocky", "berry", "candy", "cherry",
        "ginger", "kitty", "misty", "pepper", "poppy", "rosemary", "aspen", "birch", "cedar",
        "laurel", "myrtle", "basil", "mercedes", "lexus", "us", "usa", "uk", "lol", "ok", "okay",
        "yes", "no", "hi", "hey", "hello",
    )
}
