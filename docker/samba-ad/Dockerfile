# =============================================================================
#  Active Directory emulator for testing AD login: Samba 4 acting as
#  the CORP.LOCAL domain controller with test users and groups (seed.sh).
#  FOR DEVELOPMENT AND TESTS ONLY.
# =============================================================================
FROM debian:bookworm-slim

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        samba samba-ad-dc samba-ad-provision winbind ldap-utils \
 && rm -rf /var/lib/apt/lists/* \
 && rm -f /etc/samba/smb.conf

COPY entrypoint.sh seed.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/seed.sh

ENV REALM=CORP.LOCAL DOMAIN=CORP
EXPOSE 389 636
HEALTHCHECK --interval=5s --timeout=5s --retries=30 \
  CMD ldapsearch -x -H ldap://localhost -b "" -s base defaultNamingContext >/dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
