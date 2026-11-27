# Data safety declaration — NovelSpeak 1.4.0

Complete **App content → Data safety** with the answers below. The app has no backend, no
account system, no analytics, no ads and no crash-reporting SDK.

## Top-level answers

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | Nothing is transmitted to the developer or an analytics/ad SDK. The app has no server. |
| Is all user data encrypted in transit? | **Yes** (or N/A if the form allows it after "No") | Every app-initiated request uses HTTPS; the release blocks cleartext. |
| Do you provide a way for users to request data deletion? | **No account exists** | There is no server-side account or data to delete. The policy explains how to clear local data. |
| Do you have a privacy policy? | **Yes** | `https://vipulgote1999.github.io/NovelSpeak/privacy/` |
| Have you committed to the Play Families policy? | **No** | The app targets 18+ only and is not designed for children. |

If the form forces a data-type selection, select **No** for every category. Do not select
"Web browsing history" as collected: browsing stays on the device and is not sent to the
developer. The policy separately explains that the websites a user visits receive the normal
browser request, including IP address and cookies, under their own policies.

## Why "No data collected" is accurate

- **No account:** there is no sign-in, email, phone number or profile.
- **No analytics:** no Firebase, Crashlytics, Sentry, Amplitude, Mixpanel or similar SDK is
  linked. `app/src` has no analytics imports and `build.sh` links only sherpa-onnx,
  commons-compress, commons-io, the Kotlin stdlib and the AndroidX WebKit classes.
- **No ads:** no ad SDK, no advertising ID permission, no AdMob/AppLovin/Unity Ads.
- **No telemetry:** the only egress is a user-initiated diagnostics zip through Android's share
  sheet. The app never uploads it.
- **No browsing upload:** pages are loaded directly by the WebView from the site the user
  chooses. The developer never receives the URL, the page or the reading history.
- **Local storage is not collection:** reading position, library, bookmarks, imported books,
  preferences, cookies and cache stay in the app sandbox. Google's Data safety definition treats
  on-device storage as not collected.
- **Model/filter downloads are data, not user data:** the requests fetch public model archives
  and filter lists from GitHub/EasyList/uBlock. They carry no account identifier. The model
  archive is SHA-256 verified and contains no executable code.

## Edge cases a reviewer may ask about

### Google Search and Google Translate

When the user types a query or taps Translate, the app opens a normal Google page with the
query or the current page URL. This is a user-initiated navigation, not an API call, and
NovelSpeak does not receive or store the result on a server. The privacy policy discloses it.
If your reviewer treats this as "sharing web browsing history", the conservative alternative is
to answer:

- Data type: **Web browsing history**
- Collected: **No**
- Shared: **Yes**
- Purpose: **App functionality** (user-initiated translation/search)
- Optional: **Yes**
- Linked to user: **No**
- Recipient: Google

Either answer is defensible; the important thing is that the privacy policy and the form match.
The prepared policy describes the Google navigation explicitly.

### WebView cookies

Websites may set first-party cookies and local storage. Those are the website's data practices,
not NovelSpeak's. The app disables third-party cookies, does not read form fields or passwords,
and does not transmit cookies to the developer. The policy explains this.

### Diagnostics

The user taps **Share diagnostics**, the app builds a zip locally, and Android's share sheet
lets the user choose the recipient. The zip contains app version, SDK, device model, ABI,
settings snapshot (URL/token/rule values redacted), event log (URLs redacted), and the last
crash (URLs redacted). It is capped at 2 MB and is never uploaded by NovelSpeak. This is a
user-initiated transfer, so no prominent disclosure is required; the Settings card and privacy
page describe it.

### Imported EPUB/PDF/text files

The user picks a file through Android's document picker or shares it into NovelSpeak. The file is
read through a one-shot/persistable URI grant, parsed on-device, and the extracted text is stored
in app-private storage. No storage permission is requested and nothing is uploaded.

### Foreground-service diagnostics

The media playback service writes a local event log. It does not collect audio, microphone data
or device identifiers. The notification permission is optional.

## Data safety form consistency checklist

- [ ] Privacy policy URL is live and matches the app name and developer name.
- [ ] Form says **No data collected** and **No data shared**.
- [ ] Policy explains the Google Search/Translate navigations, model downloads and filter-list
      downloads.
- [ ] Policy explains local retention and how to delete local data.
- [ ] Target audience is 18+ and restrict minor access is on.
- [ ] Content rating says unrestricted web access = Yes.
- [ ] The in-app privacy page links to the same hosted policy.
