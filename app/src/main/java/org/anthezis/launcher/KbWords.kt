package org.anthezis.launcher

import android.content.Context
import java.io.File

/**
 * Word list of the AOS keyboard for one language: a built-in list of common words (in order of frequency) plus the words
 * the user types, counted and kept in a small file. Gives completions for the word being typed and tells whether a word is
 * "known" (then autocorrect leaves it alone).
 */
class KbWords(private val ctx: Context, val lang: String) {
    private val builtin: Map<String, Int> = (if (lang == "ru") RU else EN).split(' ').filter { it.isNotBlank() }
        .withIndex().associate { (i, w) -> w to i }
    private val learned = HashMap<String, Int>()
    private var dirty = false
    private val file get() = File(ctx.filesDir, "kb_learn_$lang.txt")

    init {
        runCatching {
            if (file.exists()) file.forEachLine { l ->
                val t = l.split('\t'); if (t.size == 2) t[1].toIntOrNull()?.let { learned[t[0]] = it }
            }
        }
    }

    fun known(w: String): Boolean { val k = w.lowercase(); return k in builtin || (learned[k] ?: 0) >= 2 }

    fun learn(w: String, by: Int = 1) {
        val k = w.lowercase()
        if (k.length < 2 || k.length > 30 || !k.all { it.isLetter() || it == '-' || it == '\'' }) return
        learned[k] = (learned[k] ?: 0) + by; dirty = true
        // Keep the file small: forget the rarest words when there are too many.
        if (learned.size > 6000) learned.entries.sortedBy { it.value }.take(1000).forEach { learned.remove(it.key) }
    }

    fun forget(w: String) { if (learned.remove(w.lowercase()) != null) dirty = true }

    /** Up to [n] words that start with [prefix] (longer than it), the most used first: own words, then the common ones. */
    fun complete(prefix: String, n: Int): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        fun score(w: String) = (learned[w] ?: 0) * 50 + (if (w in builtin) 1000 - minOf(999, builtin[w]!!) else 0)
        val all = HashSet<String>()
        for (w in learned.keys) if (w.length > p.length && w.startsWith(p)) all += w
        for (w in builtin.keys) if (w.length > p.length && w.startsWith(p)) all += w
        return all.sortedByDescending { score(it) }.take(n)
    }

    fun save() {
        if (!dirty) return
        dirty = false
        runCatching { file.writeText(learned.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
    }

    private companion object {
        const val RU = "и в не на я что он с как а то это по но все она так его к ты же вы за бы у от из мы о да было только " +
            "ещё еще уже меня мне тебя тебе нет вот если может когда даже очень надо нужно потом сейчас здесь там где кто " +
            "чем чтобы или тоже себя себе был была были будет буду будем хорошо привет пока спасибо пожалуйста давай " +
            "сегодня завтра вчера время день ночь утро вечер дома домой работа работе работу человек люди жизнь друг " +
            "друзья мама папа брат сестра деньги телефон сообщение напиши позвони ответь знаю знаешь думаю хочу хочешь " +
            "можно нельзя почему потому зачем сколько какой какая какие который которая новый новая большой маленький " +
            "конечно наверное вообще просто правда точно ладно окей понял поняла понятно говорит сказал сказала скажи " +
            "делать делаю делаешь сделать сделал сделала иди идти пойдём пойдем приду придёшь пришёл пришел пришла " +
            "смотри посмотри видел видела фото видео игра играть музыка фильм школа урок домашка класс учитель " +
            "доброе утро добрый спокойной ночи люблю тебя целую обнимаю скучаю извини прости круто класс супер " +
            "отлично нормально плохо устал устала занят занята свободен свободна минуту минут часов час скоро рано поздно " +
            "много мало больше меньше всегда никогда иногда опять снова вместе один одна одно два три первый последний " +
            "сообщи напомни получил получила отправил отправила проверь проверить купить магазин еда есть пить кофе чай " +
            "погода дождь снег холодно тепло жарко машина автобус дорога город улица неделя месяц год выходные праздник " +
            "поздравляю рождения счастья здоровья удачи успехов"
        const val EN = "the be to of and a in that have i it for not on with he as you do at this but his by from they we say " +
            "her she or an will my one all would there their what so up out if about who get which go me when make can like " +
            "time no just him know take people into year your good some could them see other than then now look only come " +
            "its over think also back after use two how our work first well way even new want because any these give day " +
            "most us is are was were been has had did does doing done going got thanks thank please hello hi hey okay ok " +
            "yes yeah sorry love today tomorrow yesterday tonight morning night later soon right here where why really " +
            "great nice cool awesome maybe sure need should home friend friends family message call phone talk meet see " +
            "let lets what's don't can't i'm it's you're that's didn't won't isn't game play music movie school class " +
            "something nothing everything anything someone everyone always never sometimes again together still very " +
            "much many more less little big small long last next week month weekend birthday happy congratulations"
    }
}
