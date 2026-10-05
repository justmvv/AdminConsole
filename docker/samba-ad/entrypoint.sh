#!/bin/bash
set -euo pipefail

: "${ADMIN_PASS:=Adm1n-Passw0rd!}"

if [ ! -f /var/lib/samba/private/sam.ldb ]; then
  echo "===> Провижининг домена ${REALM}"
  rm -f /etc/samba/smb.conf
  samba-tool domain provision \
      --realm="${REALM}" --domain="${DOMAIN}" --server-role=dc \
      --dns-backend=NONE --use-rfc2307 --adminpass="${ADMIN_PASS}" \
      --option="acl_xattr:security_acl_name = user.NTACL"
  # sysvol ACLs are kept in user.NTACL: security.* would require a privileged container.
  # Simple bind without TLS is allowed only for test convenience; on a real AD use ldaps://…:636
  # (provision keeps only the last --option, so we append to smb.conf ourselves)
  sed -i '/^\[global\]/a \\tldap server require strong auth = no' /etc/samba/smb.conf
  echo "===> Тестовые пользователи и группы"
  /usr/local/bin/seed.sh
fi

echo "===> Запуск Samba AD DC"
exec samba --interactive --no-process-group --debuglevel=1
