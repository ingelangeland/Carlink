# CarLink – egen «Android Auto» med to apper

| App | Installeres på | Rolle |
|---|---|---|
| **CarLink** (`launcher`) | Skjermen i bilen | Hjemmeskjerm/launcher. Alt styres herfra: kart, musikk, apper, delt skjerm. |
| **CarLink Sender** (`sender`) | Telefonen | Deler GPS, musikk (info + lyd over Wi-Fi) og Google Maps-navigasjon. Settes opp én gang, så ligger den i bakgrunnen. |
| `core` | (felles bibliotek) | Tilkobling, protokoll, lesing av musikk og navigasjon |

## Bygge og installere
1. Åpne mappen `CarLink` i Android Studio. Gradle synkroniseres automatisk.
2. Velg kjørekonfigurasjonen **launcher** og kjør på bilskjermen. Velg **sender** og kjør på telefonen.
   APK-er får du via *Build → Build APK(s)* (`launcher/build/...` og `sender/build/...`).
3. Krav: Android 10 eller nyere på begge.

## Oppsett (én gang)
**Bilskjermen**
1. Åpne CarLink og godta tillatelsene.
2. Trykk Hjem-knappen og velg **CarLink → Alltid**, eller gå til *Mer → Velg CarLink som standard hjemmeskjerm*.
3. Par skjermen med telefonen i Bluetooth-innstillingene (*Mer → Gjør synlig*).
4. Valgfritt: *Mer → Telefonens GPS i alle apper* (se under).

**Telefonen**
1. Åpne CarLink Sender og trykk *Gi tilgang til musikk og navigasjon*.
2. Velg bilskjermen i lista og trykk **Koble til**.
3. Valgfritt: **Strøm lyd til skjermen**.

Etter dette kobler enhetene seg til automatisk, og alt brukes fra bilskjermen.

## Tilkobling
Bluetooth brukes bare til å finne hverandre. Telefonen sender Wi-Fi-adressen sin over Bluetooth, og deretter går alt over Wi-Fi: GPS (1×/s), musikkinfo og cover, lyd, navigasjon og knappetrykk fra skjermen.
- **Samme nett** (anbefalt): Skjermen er koblet til telefonens hotspot eller samme Wi-Fi, og har da internett for kartet.
- **Direkte Wi-Fi:** Kryss av på telefonen. Da trengs ingen ruter, men skjermen får ikke internett.

## Ruter og GPS fra telefonen
- **Sving-for-sving på bilskjermen:** Start navigasjon i Google Maps på telefonen. CarLink Sender leser Google Maps sitt navigasjonsvarsel og viser neste sving (pil, avstand, gate, ankomsttid) som et kort øverst i kartpanelet på bilskjermen. Trykk på kortet for å åpne Google Maps på skjermen.
- **Send sted eller rute til bilen:** Trykk *Del* i Google Maps på telefonen og velg *Send til bilen*. Bilskjermen åpner stedet/ruten i Google Maps.
- **Rute skrevet inn på telefonen:** Skriv et mål under *Send rute til skjermen*. Navigasjonen starter på skjermen.
- **Lagrede steder, hjem/jobb og nylige ruter:** Logg inn i Google Maps på bilskjermen med *samme Google-konto* som på telefonen. Google synkroniserer dette selv. Andre apper kan ikke lese telefonens Google Maps-historikk direkte.
- **Telefonens GPS i alle apper:** Slå på under *Mer*, og velg CarLink som *app for simulert posisjon* i utvikleralternativene på skjermen. Da bruker også Google Maps på bilskjermen telefonens GPS, som er nyttig hvis skjermen ikke har egen GPS eller har dårlig mottak. CarLinks eget kart bruker alltid telefonens GPS.

## Lyd over Wi-Fi
- Telefonen tar opp lyden fra musikkappen (Android sin *AudioPlaybackCapture*) og sender den ukomprimert i CD-kvalitet (PCM 16-bit, 48 kHz) til skjermen.
- **Buffer:** Skjermen samler opp 600 ms lyd før den spiller (kan endres under *Mer → Lydbuffer*). Går bufferen tom, fyller den opp igjen i stedet for å hakke. Telefonen har en kø på 3 s, og Wi-Fi-strømsparing er av under tilkoblingen.
- Android spør om lov hver gang strømmingen startes.
- Apper som blokkerer opptak gir stillhet. Test Tidal først, og ikke bruk Tidals «eksklusiv modus».
- Koble telefonen fra bilens Bluetooth-lyd, ellers spilles lyden dobbelt.

## Hjemmeskjermen
- **Coveret fyller hele bakgrunnen (16:9 og 21:9):** Hele coveret vises skarpt til høyre i full høyde, uten at noe kuttes bort: 56 % av bredden på 16:9 og 43 % på 21:9. Venstre kant tones mykt ut, og resten av skjermen fylles med en uskarp versjon av samme cover. Alt annet ligger oppå. Venstre side og bunnen er tonet i coverets egen mørke farge, så tekst og knapper alltid kan leses. Coveret bytter med myk overgang når låten endres, og ligger også bak kart-, musikk- og app-panelene.
- **Alt fargelegges fra coveret:** Glasskortene, app-knappene, musikklinjen, spill-knappen, menyen, panelene, fremdriftslinjen og lydbølgene får farger hentet fra coveret, og fargene glir over ved nytt cover. Coveret sendes i 1024 px over Wi-Fi, så det blir skarpt på stor skjerm.
- **Toppen:** Vær (temperatur og symbol fra MET Norway / yr for telefonens posisjon), fart fra telefonens GPS, snarveier (innstillinger, kart, delt skjerm), tilkoblingsstatus og alle apper.
- **Venstre side:** Klokke og dato i et glasskort og favorittappene (maks 6). På 16:9 står appene under klokka. På 21:9 og bredere (også f.eks. 1920×720) får klokka og appene hver sin kolonne. Rutenettet bruker 2 eller 3 kolonner etter skjermhøyden, og klokka blir mindre på lave skjermer som 1280×720 og 1024×600.
- **Høyre side:** Står åpen, så coveret i bakgrunnen synes. Trykk der for å åpne musikkvisningen. Under navigasjon vises neste sving her.
- **Favorittapper:** Hold inne en app i app-lista og velg *Legg til på Hjem*. Hold inne en favoritt for å åpne den ved siden av eller fjerne den.
- **Musikklinje nederst:** Forrige, spill/pause, neste, cover, tittel, fremdriftslinje og lydvisning. Trykk på lydvisningen for å bytte mellom *Bølge* (myke bølger i tre lag med glød) og *Kapsler* (speilvendte, avrundede stolper). Valget huskes. Når lyden strømmes til skjermen, følger bølgene den ekte lyden (bass til venstre, diskant til høyre).
- Hjem-knappen på enheten tar deg alltid tilbake hit.

## Bilskjermen
- **Venstre meny** (i kart-, musikk-, app- og delt visning): Hjem, Kart, Musikk, Apper, *DELT 1/2/3* og Mer.
- **Dra** i streken mellom panelene for å justere fordelingen. Fordelingen huskes.
- Trykk *Kart / Musikk / Apper* øverst i et panel for å bytte innhold.
- **Apper:** Trykk for å åpne. Langt trykk gir *Åpne ved siden av* (Androids delte skjerm) eller *flytende vindu*.
- Tilbake-knappen lukker ikke hjemmeskjermen.

## Begrensninger
- Andre apper kan ikke bygges inn *i* CarLink. Det får bare systemapper som Android Auto lov til. Eksterne apper åpnes ved siden av eller i vindu.
- Kartet i CarLink er OpenStreetMap. Google Maps åpnes som egen app.
- Navigasjonskortet bygger på teksten i Google Maps sitt varsel. Endrer Google formatet, kan visningen bli litt annerledes.
