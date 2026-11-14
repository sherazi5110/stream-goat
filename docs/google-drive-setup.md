# Google Drive backup: setting up Google sign-in

Stream Goat backs up to the user's own Google Drive, in Drive's hidden **app folder** (scope
`https://www.googleapis.com/auth/drive.appdata`): the app sees only its own backup file there, never the rest of
the Drive. Sign-in uses Google's code sign-in for TVs and other devices: the app shows a code, the user enters it
at google.com/device on a phone or computer. That works the same on phones, Android TV and Windows/Linux.

For this, the project needs its own Google sign-in client. It's free and takes about ten minutes, once.

## 1. Google Cloud project

1. Open <https://console.cloud.google.com/> and sign in with the Google account that will own the project.
2. Top bar → project picker → **New project** → name it `Stream Goat` → **Create**, and select it.
3. **APIs & Services → Library** → search **Google Drive API** → **Enable**.

## 2. Consent screen (what users see when they sign in)

1. **Google Auth Platform** (in older menus: *APIs & Services → OAuth consent screen*) → **Get started**.
2. App name `Stream Goat`, your email as support email → **Next**.
3. Audience: **External** → **Next**. Contact email: yours → **Next** → agree → **Create**.
4. **Data Access → Add or remove scopes** → filter for `drive.appdata` → tick
   `.../auth/drive.appdata` ("See, create, and delete its own configuration data in your Google Drive") →
   **Update** → **Save**. It's a *non-sensitive* scope, so no review by Google is needed.
5. **Audience → Publish app → Confirm** (status *In production*). In *Testing* only the test users you list can
   sign in, and their sign-in expires after 7 days.

Don't upload a logo: a logo makes Google ask for a brand review first.

## 3. The client

1. **Clients** (older: *Credentials*) → **Create client** (*Create credentials → OAuth client ID*).
2. Application type: **TVs and Limited Input devices**. Name: `Stream Goat` → **Create**.
3. Copy the **Client ID** and the **Client secret**.

For an app that is installed on people's devices, Google doesn't treat this client secret as a secret: it is built
into the app. Still, keep it in GitHub secrets rather than in the code.

## 4. GitHub secrets

Repository → **Settings → Secrets and variables → Actions → New repository secret**, twice:

| Name | Value |
| --- | --- |
| `GOOGLE_CLIENT_ID` | the Client ID (ends in `.apps.googleusercontent.com`) |
| `GOOGLE_CLIENT_SECRET` | the Client secret |

The next build (test build or release) has Google Drive backup. Builds without the secrets show *"Google sign-in
isn't set up in this build"*.

For a local build, set the same two environment variables before running Gradle. The Windows/Linux app also reads
`STREAMGOAT_GOOGLE_CLIENT_ID` / `STREAMGOAT_GOOGLE_CLIENT_SECRET` at runtime.

## What users see

Settings → *Updates and backup* (Android) or *Storage* (Windows/Linux) → **Google Drive backup → Sign in with
Google**: a code and a QR code for google.com/device. After signing in, a backup already in the account is
offered for restoring; otherwise this device's backup is saved there. With *Back up automatically* on (default),
it's uploaded whenever something changed (checked every 15 minutes, and on Android when the app is put away).
On a fresh install, the first screen has **Restore from Google Drive**.

Users can remove the backup in Google Drive → Settings → *Manage apps* → Stream Goat → *Delete hidden app data*,
and Stream Goat's access in their Google account → *Security → Third-party apps*.
