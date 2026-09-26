# How to Use LIFELiNK

> **Hackathon demo:** LIFELiNK helps you contact people you have registered. It is not an emergency-services app and does not contact police, fire, ambulance, 911, 112, 110, or 119. Do not rely on it as your only way to get help.

## Download

[**Download LIFELiNK for Android (APK)**](https://github.com/rtree/LIFELiNK/releases/latest/download/LIFELiNK-demo-v0.1.0.apk)

Current demo version: **0.1.0**  
SHA-256: `735749a09365b8fb1a1f75094d8abff5ffb39a7c0572912d83bc095b68aec126`

## Requirements

Before installing LIFELiNK, make sure you have:

- A physical Android phone running **Android 8.0 or later**.
- An active SIM and phone number issued by a mobile carrier in the **same country where you are using LIFELiNK**. International roaming does not meet this demo requirement.
- A mobile plan and carrier that support **carrier conference calls (three-way calling / call merging)**. LIFELiNK uses this feature to connect your phone, your trusted contact, and the AI assistant.
- A **Google account** for signing in to LIFELiNK.
- A **World ID** and the **World App** installed on the same phone to complete Proof of Human verification.
- A trusted contact who has agreed to receive a demo call. Standard carrier calling charges may apply.
- Mobile data or Wi-Fi, Bluetooth, and location services enabled.

Carrier conference calling varies by country, carrier, subscription, and device. The full SOSV2 experience has been tested on a Samsung Galaxy with a SoftBank SIM in Japan; other combinations may behave differently.

## Install the APK

1. Open the APK download link above on your Android phone.
2. When Android asks for permission, allow your browser or file manager to **install unknown apps**.
3. Open the downloaded `LIFELiNK-demo-v0.1.0.apk` file and tap **Install**.
4. Open **LIFELiNK** after installation.
5. Disable the unknown-app installation permission again after the installation is complete.

This APK is distributed directly from the official LIFELiNK GitHub repository and is not published through Google Play. Only install a file downloaded from the link on this page.

## Set Up LIFELiNK

1. **Sign in with Google.** Select the Google account you want to use with the demo.
2. **Verify with World ID.** Open the **Settings** tab, start World ID verification, approve the Proof of Human request in World App, and return to LIFELiNK. Wait until the app shows that verification is complete.
3. **Allow permissions.** Grant phone, notification, Bluetooth, and location permissions when requested. These permissions support calls, the optional BLE button, and coarse location context.
4. **Add your profile.** Enter the profile details shown in Settings.
5. **Register a trusted contact.** Enter a consenting contact's phone number in international E.164 format, for example `+819012345678`.
6. **Enable conference-call mode.** Make LIFELiNK your default phone app when Android asks. This is required for SOSV2 to place and merge the two carrier calls automatically.
7. **Allow background operation.** Set LIFELiNK's battery usage to **Unrestricted** so Android is less likely to stop monitoring while the phone is locked.
8. **Optional: link a +Beacon.** Follow the in-app instructions to link the physical BLE button, then start monitoring.

While LIFELiNK is the default phone app, it also handles the phone's normal incoming and outgoing call interface. You can restore your previous phone app at any time in Android Settings.

## Run the Demo

1. Confirm that the registered contact is ready and has agreed to receive the call.
2. Open the **Home** tab and check that Google sign-in, World ID verification, the contact, and phone-app setup are ready.
3. Trigger SOS with either:
   - three quick taps on the on-screen SOS button;
   - a two-second press on the on-screen SOS button; or
   - a long press on a linked +Beacon.
4. LIFELiNK calls the AI number and your trusted contact from your SIM, then asks the carrier to merge both calls.
5. When the contact answers, the AI provides the available context and can listen to the conference call. The Home tab shows the event feed.
6. End the call from the LIFELiNK call screen when the demo is complete.

Use only your own phone number and consenting recipients during the demo. Do not test with emergency-service numbers.

## Troubleshooting

**The APK will not install**  
Make sure the phone runs Android 8.0 or later and that your browser or file manager is temporarily allowed to install unknown apps.

**World ID verification does not open**  
Install or update World App, confirm that your World ID is available, and start verification again from LIFELiNK Settings.

**The calls do not merge**  
Confirm that LIFELiNK is the default phone app and that your local carrier plan supports three-way calling. Conference-call behavior is controlled by the carrier and may not work while roaming.

**The physical button does not trigger an SOS**  
Enable Bluetooth and location, set battery usage to Unrestricted, link the +Beacon again, and start monitoring from the app.

**You no longer want LIFELiNK to handle regular calls**  
Open Android Settings, search for **Default apps** or **Phone app**, and select your previous phone app.

## Privacy and Safety

LIFELiNK may process the registered contact's phone number, coarse location context, event metadata, call transcripts, and participating account identifiers. Call audio files are not stored by the current prototype. Review the [project README](README.md) for architecture, limitations, and the current consent gaps before running the demo.
