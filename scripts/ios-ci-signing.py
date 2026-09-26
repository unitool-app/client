#!/usr/bin/env python3
"""
Checks the App Store profile against the certificate and the app, then points the build at both.

    python3 scripts/ios-ci-signing.py <profile.plist> <identity SHA-1> <identity name>

The testflight lane of ios.yml runs it after `security cms -D` has decoded IOS_PROFILE_BASE64 and
the .p12 has gone into the keychain. Every mismatch it looks for is one xcodebuild would also find,
twenty minutes later and in words that do not say which secret is wrong: a profile for another app,
a development or ad-hoc profile, an App ID without Push, a profile made for another certificate.

What it writes, both for this run only (neither is committed):

  iosApp/Configuration/Signing.xcconfig   manual signing, the identity, the profile. Config.xcconfig
                                           includes it, so it reaches the app target and nothing
                                           else — on the command line PROVISIONING_PROFILE_SPECIFIER
                                           would reach Firebase's resource bundles, which refuse one.
  build/ExportOptions.plist                ExportOptions.plist with the same identity and profile.

Prints the profile's UUID, the name the runner files it under.
"""
import hashlib
import os
import plistlib
import sys

CONFIG = "iosApp/Configuration"


def xcconfig(name):
    with open(os.path.join(CONFIG, "Config.xcconfig")) as f:
        for line in f:
            key, _, value = line.partition("=")
            if key.strip() == name:
                return value.strip()
    sys.exit(f"{name} is not in {CONFIG}/Config.xcconfig")


def main(profile_path, identity_sha1, identity_name):
    team, bundle = xcconfig("TEAM_ID"), xcconfig("BUNDLE_ID")
    with open(profile_path, "rb") as f:
        profile = plistlib.load(f)
    entitlements = profile.get("Entitlements", {})

    problems = []
    app_id = entitlements.get("application-identifier", "")
    if app_id != f"{team}.{bundle}":
        problems.append(f"it is for {app_id or 'no App ID'}, not {team}.{bundle}")
    if "ProvisionedDevices" in profile or entitlements.get("get-task-allow"):
        problems.append("it is a development or ad-hoc profile; TestFlight takes an App Store one")
    if entitlements.get("aps-environment") != "production":
        problems.append("the App ID has no Push Notifications; turn it on, then make the profile again")
    certificates = {hashlib.sha1(c).hexdigest().upper() for c in profile.get("DeveloperCertificates", [])}
    if identity_sha1.upper() not in certificates:
        problems.append(f"it was made for another certificate than the one in the .p12 ({identity_name})")
    if problems:
        for problem in problems:
            print(f"::error::IOS_PROFILE_BASE64 «{profile.get('Name', '?')}»: {problem}", file=sys.stderr)
        sys.exit(1)

    uuid = profile["UUID"]
    with open(os.path.join(CONFIG, "Signing.xcconfig"), "w") as f:
        f.write("// Written by scripts/ios-ci-signing.py for one CI run. Not committed.\n"
                "CODE_SIGN_STYLE=Manual\n"
                f"CODE_SIGN_IDENTITY={identity_name}\n"
                f"PROVISIONING_PROFILE_SPECIFIER={uuid}\n")

    with open(os.path.join(CONFIG, "ExportOptions.plist"), "rb") as f:
        options = plistlib.load(f)
    options["signingCertificate"] = identity_name
    options["provisioningProfiles"] = {bundle: uuid}
    os.makedirs("build", exist_ok=True)
    with open("build/ExportOptions.plist", "wb") as f:
        plistlib.dump(options, f)

    print(uuid)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
