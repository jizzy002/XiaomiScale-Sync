#!/usr/bin/env python3
"""Mint Garmin DI OAuth tokens for the Forerunner 255 watch app.

One-time helper for the watch app's fallback sign-in route: the watch
stores a DI refresh token + client id in its Connect IQ settings and
refreshes access tokens on its own. This script performs the same SSO
sign-in the Android app uses (mobile iOS SSO -> DI token exchange) and
prints the two values you paste into the watch's settings.

Security notes (repo ground rule 3):
  * Tokens are printed to this terminal ONLY. Nothing is written to any
    file, log, clipboard or the repo.
  * Your password is read via getpass (never echoed, never stored).
  * Close or clear this terminal when you are done.

Port of app/src/main/java/com/xiaomiscalesync/garmin/GarminClient.kt's
ssoLogin/verifyMfa/exchangeServiceTicket (same endpoints, headers and
client-id walk as the reference python-garminconnect client).

Usage:
    python scripts/garmin_token.py
"""

import base64
import getpass
import json
import sys

try:
    import requests
except ImportError:
    sys.exit("This script needs the 'requests' package: pip install requests")

SSO_BASE = "https://sso.garmin.com"
DIAUTH_BASE = "https://diauth.garmin.com"

# The reference's iOS mobile-app SSO constants (GarminClient.kt).
IOS_SSO_CLIENT_ID = "GCM_IOS_DARK"
IOS_SERVICE_URL = "https://mobile.integration.garmin.com/gcm/ios"
IOS_LOGIN_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
)

# Portal fallback constants (MFA verify alternate endpoint).
PORTAL_SSO_CLIENT_ID = "GarminConnect"
PORTAL_SERVICE_URL = "https://connect.garmin.com/app"

DI_TOKEN_PATH = "/di-oauth2-service/oauth/token"
DI_GRANT_TYPE = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"

# The reference's DI client-id walk, in order, until one is accepted.
DI_CLIENT_IDS = [
    "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
    "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
    "GARMIN_CONNECT_MOBILE_ANDROID_DI",
    "GARMIN_CONNECT_MOBILE_IOS_DI",
]

# The reference's native Android header block.
NATIVE_API_USER_AGENT = "GCM-Android-5.23"
NATIVE_X_GARMIN_USER_AGENT = (
    "com.garmin.android.apps.connectmobile/5.23; ; "
    "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0"
)


def native_headers() -> dict:
    return {
        "User-Agent": NATIVE_API_USER_AGENT,
        "X-Garmin-User-Agent": NATIVE_X_GARMIN_USER_AGENT,
        "X-Garmin-Paired-App-Version": "10861",
        "X-Garmin-Client-Platform": "Android",
        "X-App-Ver": "10861",
        "X-Lang": "en",
        "X-GCExperience": "GC5",
        "Accept-Language": "en-US,en;q=0.9",
    }


class GarminSignInError(Exception):
    """A typed sign-in refusal; message is safe to print (no secrets)."""


def sso_login(session: requests.Session, email: str, password: str) -> str:
    """The reference's _do_mobile_login. Returns the service ticket."""
    url = f"{SSO_BASE}/mobile/api/login"
    params = {"clientId": IOS_SSO_CLIENT_ID, "locale": "en-US", "service": IOS_SERVICE_URL}
    response = session.post(
        url,
        params=params,
        json={"username": email, "password": password, "rememberMe": True, "captchaToken": ""},
        headers={
            "User-Agent": IOS_LOGIN_UA,
            "Accept": "application/json, text/plain, */*",
            "Origin": "https://sso.garmin.com",
        },
        timeout=30,
    )
    try:
        payload = response.json()
    except ValueError:
        raise GarminSignInError(f"Garmin SSO returned unparseable JSON (HTTP {response.status_code})")
    status = (payload.get("responseStatus") or {}).get("type")
    if status == "SUCCESSFUL":
        ticket = payload.get("serviceTicketId")
        if not ticket:
            raise GarminSignInError("Garmin SSO login returned no service ticket")
        return ticket
    if status == "MFA_REQUIRED":
        info = payload.get("customerMfaInfo") or {}
        method = info.get("mfaLastMethodUsed") or "email"
        return verify_mfa(session, method)
    if status == "INVALID_USERNAME_PASSWORD":
        raise GarminSignInError("Invalid Garmin username or password")
    error = payload.get("error") or {}
    if str(error.get("status-code")) == "429":
        raise GarminSignInError("Garmin SSO login is rate limited - wait a while and retry")
    raise GarminSignInError(f"Garmin SSO login failed (responseStatus={status or 'unknown'})")


def verify_mfa(session: requests.Session, method: str) -> str:
    """The reference's _complete_mfa: mobile endpoint, portal fallback."""
    code = input(f"Garmin sent an MFA code to your {method}. Enter the code: ").strip()
    body = {
        "mfaMethod": method,
        "mfaVerificationCode": code,
        "rememberMyBrowser": True,
        "reconsentList": [],
        "mfaSetup": False,
    }
    endpoints = [
        ("/mobile/api/mfa/verifyCode", IOS_SSO_CLIENT_ID, IOS_SERVICE_URL),
        ("/portal/api/mfa/verifyCode", PORTAL_SSO_CLIENT_ID, PORTAL_SERVICE_URL),
    ]
    for path, client_id, service in endpoints:
        response = session.post(
            f"{SSO_BASE}{path}",
            params={"clientId": client_id, "locale": "en-US", "service": service},
            json=body,
            headers={
                "User-Agent": IOS_LOGIN_UA,
                "Accept": "application/json, text/plain, */*",
                "Origin": "https://sso.garmin.com",
            },
            timeout=30,
        )
        if response.status_code == 200:
            try:
                payload = response.json()
            except ValueError:
                continue
            if (payload.get("responseStatus") or {}).get("type") == "SUCCESSFUL":
                ticket = payload.get("serviceTicketId")
                if ticket:
                    return ticket
    raise GarminSignInError("Garmin MFA verification failed")


def exchange_service_ticket(session: requests.Session, ticket: str):
    """The reference's _exchange_service_ticket: walk DI client ids until
    Garmin mints a DI token. Returns (client_id, refresh_token, expires_in)."""
    for client_id in DI_CLIENT_IDS:
        headers = native_headers()
        headers.update(
            {
                # Basic auth is "clientId:" with an empty password.
                "Authorization": "Basic " + base64.b64encode(f"{client_id}:".encode()).decode(),
                "Accept": "application/json,text/html;q=0.9,*/*;q=0.8",
                "Content-Type": "application/x-www-form-urlencoded",
                "Cache-Control": "no-cache",
            }
        )
        response = session.post(
            DIAUTH_BASE + DI_TOKEN_PATH,
            data={
                "client_id": client_id,
                "service_ticket": ticket,
                "grant_type": DI_GRANT_TYPE,
                "service_url": IOS_SERVICE_URL,
            },
            headers=headers,
            timeout=30,
        )
        if response.status_code != 200:
            continue
        try:
            tokens = response.json()
        except ValueError:
            continue
        if not tokens.get("access_token"):
            continue
        return client_id, tokens.get("refresh_token"), tokens.get("expires_in")
    raise GarminSignInError("Garmin DI token exchange failed for every client id")


def main() -> None:
    print("Garmin DI token helper for the XiaomiScale Sync watch app.")
    print("(Tokens are printed to this terminal only; nothing is saved.)\n")
    email = input("Garmin email: ").strip()
    password = getpass.getpass("Garmin password: ")
    session = requests.Session()
    try:
        ticket = sso_login(session, email, password)
    except GarminSignInError as error:
        sys.exit(f"\nFailed: {error}")
    print("Signed in; exchanging the service ticket for DI tokens...")
    try:
        client_id, refresh_token, expires_in = exchange_service_ticket(session, ticket)
    except GarminSignInError as error:
        sys.exit(f"\nFailed: {error}")
    if not refresh_token:
        sys.exit("\nFailed: Garmin returned no refresh token for this account")
    hours = f" (~{int(expires_in) // 3600}h access-token lifetime)" if expires_in else ""
    print("\n=== Copy these two values into the watch's Connect IQ settings ===")
    print(f"\n  Garmin client id:      {client_id}")
    print(f"  Garmin refresh token:  {refresh_token}")
    print(f"\n  (access token expires in {expires_in or '?'} seconds{hours};")
    print("   the watch refreshes it by itself.)")
    print("\nClear this terminal when done.")


if __name__ == "__main__":
    main()
