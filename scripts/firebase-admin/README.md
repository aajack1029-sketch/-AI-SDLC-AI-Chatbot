# Firebase user role administration

Run this administrative script only on a trusted machine. It uses Google Application Default Credentials (ADC); it does not need a service-account key file.

## One-time setup

1. Install the Google Cloud CLI and sign in with an administrator account:

   ```powershell
   gcloud auth application-default login
   ```

2. Ensure that account has permission to read and update Firebase Authentication users in the target project (for example, **Firebase Authentication Admin**, `roles/firebaseauth.admin`).
3. Install this script's dependencies:

   ```powershell
   cd scripts\firebase-admin
   npm install
   ```

## Assign a role

From `scripts\firebase-admin`, run:

```powershell
npm run set-role
```

The script asks for the Firebase Project ID, the user's Firebase Authentication email, and one role (`DEVELOPER`, `MANAGER`, or `ADMIN`). The default project ID is `sdlc-ai-chatbot-e017d`; press Enter to use it. Review the project, account, and role, then type `APPLY` to confirm.

The script preserves other existing custom claims and replaces the user's `roles` claim with the selected role. It never prints or requests a UID or private key. The user must sign out and sign in again to receive a refreshed ID token containing the new claim.

`DEVELOPER` is the current internal role name for employees. `ADMIN` is recognized as an identity only: the chatbot API does not grant it chat or report-search access, and user-management, audit-log, and Qdrant administration features are not implemented yet. Assigning this claim alone does not grant those capabilities.

## Troubleshooting

If Firebase reports that `identitytoolkit.googleapis.com` is disabled, first check the target project ID printed by the script. It must match the Firebase project that owns the user account. For this application, that ID is `sdlc-ai-chatbot-e017d`; do not enable the API in a different project just because it appears in an error link.

Enable the API in the intended project with:

```powershell
gcloud services enable identitytoolkit.googleapis.com --project=sdlc-ai-chatbot-e017d
```

Alternatively, open the API link from the error and confirm its selected project is `sdlc-ai-chatbot-e017d` before enabling the API. Wait a few minutes for the change to take effect, then rerun the script. If the API is enabled but access still fails, verify that the signed-in Google account has Firebase Authentication Admin permissions on this project.

If the script prints `sdlc-ai-chatbot-e017d` but the error link names a different project, the ADC quota project may be set to that other project. Set it to the Firebase project (the account needs Service Usage Consumer permission there):

```powershell
gcloud auth application-default set-quota-project sdlc-ai-chatbot-e017d
```

Then rerun the script. Do not change the quota project or enable APIs in `gen-lang-client-0156730472` unless that is intentionally the project that owns the Firebase users.

Do not run this script in the frontend or put administrator credentials in a Vite `.env` file. Do not commit credentials or tokens.
