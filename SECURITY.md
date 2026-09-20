# Security policy

StoreQL runs the tills, the online shop and the books of the businesses that use it. A weakness in it
can expose their customers' personal data or their money, so reports are welcome and taken seriously.

## Reporting a vulnerability

Report privately — never in a public issue, pull request or discussion.

- Use the contact published at **`/.well-known/security.txt`** on the deployment you found the problem
  in ([RFC 9116](https://www.rfc-editor.org/rfc/rfc9116)). Each deployment configures its own contact,
  so the file on the site you were testing is the right address.
- For a problem in this source code rather than a deployment, use GitHub's private vulnerability
  reporting on this repository where it is enabled.

Please include what you found, where (the URL, endpoint or file), how to reproduce it, what an attacker
could do with it, and whether you believe it is being exploited. Include no customer data you came
across beyond what is needed to show the problem.

## What happens next

We confirm we have the report, keep you told of progress, and agree a disclosure date with you once a
fix is available.

A vulnerability that is being **actively exploited** in the software, or a **severe incident**
affecting its security, is reported to the authorities as the EU Cyber Resilience Act
(Regulation (EU) 2024/2847, art.14) requires: an early warning within 24 hours of becoming aware of it,
a notification within 72 hours, and a final report once a corrective measure is available. Businesses
affected are told directly, and a personal data breach is reported to each business affected, as its
processor, without undue delay (UK GDPR / GDPR art.33(2)). The platform keeps these clocks in its
security incident register (Platform console → Security incidents).

## Scope and conduct

In scope: the services, gateway and apps in this repository, and deployments of them that publish a
`security.txt` naming this policy. Out of scope: denial of service, social engineering, physical
attacks, and findings that need a compromised device.

Test only against accounts and data you own or have permission to use; stop and report as soon as you
reach data that is not yours; do not degrade the service for others. Research done in good faith within
these limits will not be pursued.
