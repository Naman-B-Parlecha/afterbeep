# Security policy

Afterbeep relies on Android's debugging service (adb) to run its call engine as the shell user, so security
reports are taken seriously.

## Reporting a vulnerability

Please **don't open a public issue**. Use GitHub's private
[security advisory form](https://github.com/agarwalvivek29/afterbeep/security/advisories/new) instead.
Include the Afterbeep version, phone model / Android version, and steps to reproduce. You'll get a reply within
a few days.

## Scope

In scope:
- Ways for another device (same Wi-Fi, USB, Bluetooth…) or another app to gain shell access through Afterbeep's
  debugging setup — e.g. bypassing the prompt guard or the random-port rotation.
- Ways to read or tamper with recorded messages, the greeting, or Afterbeep's adb key.
- Anything that makes the caller hear your microphone, or people near you hear the caller.

Out of scope:
- Android/OEM vulnerabilities unrelated to Afterbeep (report those to Google or the vendor).
- Attacks that require you to approve an adb authorization prompt *with the lock turned off*.

## Design notes

See the *Security model* section of the [README](README.md#security-model).
