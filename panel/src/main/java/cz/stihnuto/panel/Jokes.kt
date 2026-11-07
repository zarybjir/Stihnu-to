package cz.stihnuto.panel

/**
 * Zabudované české vtipy a slovní hříčky — ne překlady z angličtiny, aby dávaly
 * smysl. První je ten „Brutopýr"; další přibývají v pořadí a pak se cyklí.
 */
object Jokes {
    private val list = listOf(
        "Co se stane z netopýra, když ho zabalím do krabice? Brutopýr.",
        "Přijde kostra do hospody a povídá: Jedno pivo. A hadr.",
        "Co řekne nula osmičce? Hezký pásek!",
        "Potkají se dva psi. Jeden štěkne: Haf! A ten druhý: No jo, to jsem ti chtěl říct zrovna já.",
        "Pepíčku, vyjmenuj čtyři kočkovité šelmy. No přece dvě kočky a dvě koťata.",
        "Víš, jak se řekne japonsky řidič? Takuji.",
        "Leze veš po hlavě a říká si: Konečně jsem doma.",
        "Baví se dvě tužky. Jedna povídá: Ty vypadáš unaveně. A ta druhá: Jsem dneska úplně tupá.",
        "Ptá se učitelka: Pepíčku, co víš o Mrtvém moři? A Pepíček: Já ani nevěděl, že bylo nemocné.",
        "Sedí dva hrášky na talíři. Jeden spadne a ten druhý povídá: Tak co, kulišáku, jak ses skulil?",
        "Povídá šnek šnekovi: Pospěš si, nebo nám ujede autobus. A za hodinu: No vidíš, ujel.",
        "Proč nosí sloni zelené trenky? Aby je nebylo vidět v trávě. A viděl jsi někdy slona v trávě? No vidíš, funguje to.",
        "Ptá se syn: Tati, proč se moře nevylije? A táta: Protože má břehy, synku.",
        "Co dělá pavouk na počítači? Pracuje na webu.",
        "Dva bleší vyjdou z kina. Jeden povídá: Půjdeme pěšky, nebo chytíme psa?",
    )

    fun next(app: App): String {
        val i = app.settings.jokeIndex
        app.settings.jokeIndex = i + 1
        return list[i % list.size]
    }

    /** Je to žádost o vtip? */
    fun isRequest(text: String) =
        Regex("\\bvtip|rozesm[ěe]j|n[ěe]co vtipn|pobav", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
