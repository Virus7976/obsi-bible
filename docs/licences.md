# Licences

One row per text, audio set or data source the app uses. The app shows each copyright line where its licence asks.

| Source | What | Licence | Offline | Credit the app shows |
|---|---|---|---|---|
| Berean Standard Bible (BSB) | Text | Public domain | Yes | "The Holy Bible, Berean Standard Bible, BSB" |
| BSB audio: Bob Souer, Barry Hays, Jordan Gilbert (openbible.com) | Audio, one MP3 per chapter | Public domain (CC0 1.0) | Cached on the phone as it plays | "Recorded · public domain" |
| Bible Hub player timing files (biblehub.com/audio/player/timing_*) | When each verse starts in those recordings | No licence stated. Read once per chapter, the same files Bible Hub's own player reads, and kept on the phone. Without them the app estimates verse times. | Yes, after the first play | none |
| Phone text-to-speech | The phone's own voice | Part of Android | Yes | none |
| Microsoft Azure Speech | Neural voices | Microsoft's terms. User's own key and region. | No | none |
| Newsreader font (regular and italic) | Bible text | SIL Open Font License | Yes | none |
| Figtree font | Interface | SIL Open Font License | Yes | none |
| ESV (api.esv.org) | Text, audio | Crossway API terms. Non-commercial. User's own key. | At most 500 verses or half a book stored | "Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), © 2001 by Crossway, a publishing ministry of Good News Publishers. Used by permission. All rights reserved." plus "ESV" on each quote and a link to esv.org |

Rows are added as each source ships.

## Notes from the research (2026-11-04)

- ESV: the key may not be shared or published, so it is never in this repo or the APK. Audio is streamed; storing it offline needs Crossway's OK.
- The Message (MSG): no public API and no free licence. It shows only if the user's own YouVersion or API.Bible key is licensed for it.
- eBible.org: each translation's licence comes from its catalogue. The installer only installs texts that allow it.
- Microsoft Edge "Read Aloud" voices (the ones Readest uses) work by pretending to be the Edge browser. The app does not do that. It uses Microsoft's official Azure Speech service with the user's own key instead.
- API.Bible (rest.api.bible): free Starter plan, non-commercial, up to 3 licensed Bibles. Must show a visible link to https://api.bible. Cached text must be refreshed at least every 30 days. Text-to-audio of a licensed text may be a derivative work, so the app never reads these Bibles with a computer voice.
- YouVersion Platform (api.youversion.com): user's own app key, header X-YVP-App-Key. Non-commercial. The app shows each Bible's copyright and names YouVersion. Same 500-verse cache cap and no computer voice, to stay safe.


| Literata font (reader text) | SIL Open Font License 1.1 | docs/Literata-OFL.txt |
