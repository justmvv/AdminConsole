#!/bin/bash
# Test AD users. Passwords are for the emulator only.
#
#  login      password         group                        expected role
#  ivanov     Viewer-2026!     SG-OrchConsole-Viewers       VIEWER
#  petrov     Operator-2026!   SG-OrchConsole-Operators     OPERATOR
#  sidorov    Admin-2026!      SG-OrchConsole-Admins        ADMIN
#  smirnov    Nobody-2026!     (no console groups)          login refused (no roles)
#  kuznetsov  Duty-2026!       Duty-Team ⊂ Operators        nested group
#  blocked    Blocked-2026!    SG-OrchConsole-Operators     account disabled
#  expired    Expired-2026!    SG-OrchConsole-Viewers       must change password
set -euo pipefail

user() { samba-tool user create "$1" "$2" --given-name="$3" --surname="$4" --mail-address="$1@corp.local" >/dev/null; }
group() { samba-tool group add "$1" --description="$2" >/dev/null; }
member() { samba-tool group addmembers "$1" "$2" >/dev/null; }

samba-tool ou create "OU=Groups" >/dev/null
group SG-OrchConsole-Viewers   "Консоль оркестратора: просмотр"
group SG-OrchConsole-Operators "Консоль оркестратора: оператор"
group SG-OrchConsole-Admins    "Консоль оркестратора: администратор"
group Duty-Team                "Дежурная смена (вложена в операторов)"

user ivanov    'Viewer-2026!'   Иван    Иванов
user petrov    'Operator-2026!' Пётр    Петров
user sidorov   'Admin-2026!'    Сидор   Сидоров
user smirnov   'Nobody-2026!'   Семён   Смирнов
user kuznetsov 'Duty-2026!'     Кузьма  Кузнецов
user blocked   'Blocked-2026!'  Борис   Блокированный
user expired   'Expired-2026!'  Ефим    Просроченный

member SG-OrchConsole-Viewers   ivanov,expired
member SG-OrchConsole-Operators petrov,blocked,Duty-Team
member SG-OrchConsole-Admins    sidorov
member Duty-Team                kuznetsov

samba-tool user disable blocked >/dev/null
# pwdLastSet=0 — "must change password at next logon" (AD returns error 773)
samba-tool user setpassword expired --newpassword='Expired-2026!' --must-change-at-next-login >/dev/null

samba-tool user list | sort | tr '\n' ' '; echo
