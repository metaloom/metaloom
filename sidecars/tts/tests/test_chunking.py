"""
Text chunking.

The defect these pin: every engine here generates at most N tokens per pass, which is a ceiling
on the DURATION of one generation regardless of how much text went in. Longer text came back cut
off mid-word, with no error and no warning anywhere — reported from the field as 1338 characters
submitted and a 49.83 s file that ends mid-sentence.
"""

from tts.chunking import absorb_short_tail, looks_like_an_abbreviation, split_for_synthesis

NEWS = (
    "Wien-News vom 15. und 16. September 2026. "
    "FPÖ und Grüne starteten ihre Herbstkampagnen im Rathaus. "
    "Die FPÖ kündigte eine Sondersitzung an und will den Bürgermeister ersetzen, "
    "während die Grünen einen Sondergemeinderat zum Hitzeschutz verlangen. "
    "Der Oberste Gerichtshof hob das Urteil im sogenannten Schamanen-Verfahren teilweise auf. "
    "Zu Gewerbsmäßigkeit, Vermögensverfall und Strafhöhe muss neu entschieden werden. "
    "Die Staatsanwaltschaft Wien bestätigte ein Ermittlungsverfahren gegen einen Künstler. "
    "Der Flughafen Wien fordert eine spürbare Senkung der Flugabgabe und verweist dabei "
    "auf eine Umwegrentabilität von achtzig Millionen Euro. "
    "Die Passagierzahlen sanken im August um sechs Komma neun Prozent. "
    "Der ATX lag am Mittwochvormittag null Komma sechs vier Prozent im Plus, "
    "als Hintergrund wurde ein positives europäisches Marktumfeld genannt."
)


def test_short_text_is_a_single_chunk():
    assert split_for_synthesis("Guten Tag.", 500) == ["Guten Tag."]
    assert split_for_synthesis("   ", 500) == []


def test_no_ceiling_means_no_split():
    """`chunk_chars = 0` is how an engine without a duration ceiling says so."""
    assert split_for_synthesis(NEWS, 0) == [NEWS]


def test_no_chunk_exceeds_the_budget():
    for limit in (120, 300, 500):
        for chunk in split_for_synthesis(NEWS, limit):
            assert len(chunk) <= limit


def test_chunking_loses_nothing():
    """Nothing dropped, nothing reordered — the join is the input, whitespace normalised."""
    chunks = split_for_synthesis(NEWS, 300)
    assert len(chunks) > 1
    assert " ".join(chunks) == " ".join(NEWS.split())


def test_chunks_end_at_sentence_boundaries():
    for chunk in split_for_synthesis(NEWS, 300)[:-1]:
        assert chunk.endswith((".", "!", "?", "…")), chunk


def test_a_single_long_sentence_never_breaks_mid_word():
    """No sentence seam available, so it falls to clauses and then to words."""
    sentence = ("Die Staatsanwaltschaft bestätigte ein Verfahren, "
                "nannte aber weder den Namen noch den Zeitraum, "
                "und verwies auf die laufenden Ermittlungen sowie auf die Unschuldsvermutung "
                "in diesem besonders sensiblen Fall der vergangenen Woche.")
    chunks = split_for_synthesis(sentence, 80)
    assert len(chunks) > 1
    assert all(len(c) <= 80 for c in chunks)
    assert " ".join(chunks) == " ".join(sentence.split())
    # every chunk is made of whole words of the original
    assert [w for c in chunks for w in c.split()] == sentence.split()


def test_an_unbreakable_token_is_still_split_rather_than_refused():
    """Total by construction: no input may come back empty or loop."""
    assert [len(c) for c in split_for_synthesis("a" * 250, 100)] == [100, 100, 50]


def test_an_abbreviation_is_not_a_sentence_end():
    """"z. B." and "bzw." carry a period; breaking there would pause mid-clause."""
    text = "Das gilt z. B. für Wien und bzw. auch für Graz. Danach folgt der zweite Satz."
    assert split_for_synthesis(text, 200) == [text]
    # …and the guard is per TOKEN, not per suffix: "Graz." ends in "z." and is a real sentence
    # end, which is why the check asks the helper rather than the string.
    assert not looks_like_an_abbreviation("Danach folgt Graz.")
    for chunk in split_for_synthesis(text, 50):
        assert not looks_like_an_abbreviation(chunk), chunk


# ------------------------------------------------------------------- the short tail
def test_a_short_tail_is_absorbed_rather_than_generated_alone():
    """A greedy split leaves the remainder in the last chunk, which can be two words."""
    assert absorb_short_tail(["Ein langer erster Teil mit viel Text", "Danke."], 20) == \
        ["Ein langer erster Teil mit viel Text Danke."]


def test_a_long_enough_tail_is_left_alone():
    chunks = ["Ein langer erster Teil", "Ein zweiter Teil der lang genug ist"]
    assert absorb_short_tail(chunks, 20) == chunks


def test_a_single_short_request_has_nothing_to_absorb():
    """A request whose entire text is "Ja." is the engine's problem, not the splitter's."""
    assert absorb_short_tail(["Ja."], 60) == ["Ja."]
    assert absorb_short_tail([], 60) == []


def test_absorbing_is_off_when_the_engine_sets_no_floor():
    chunks = ["Ein langer erster Teil mit viel Text", "Danke."]
    assert absorb_short_tail(chunks, 0) == chunks


def test_absorbing_loses_no_text():
    chunks = split_for_synthesis(NEWS, 300)
    assert " ".join(absorb_short_tail(chunks, 120)) == " ".join(chunks)


def test_a_merged_chunk_stays_within_the_engine_ceiling():
    """limit + minimum, which is well under what one generation can say."""
    chunks = absorb_short_tail(split_for_synthesis(NEWS, 300), 60)
    assert all(len(c) <= 300 + 60 for c in chunks)
