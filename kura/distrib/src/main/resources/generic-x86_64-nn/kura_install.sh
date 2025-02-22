#!/bin/sh
#
# Copyright (c) 2023, 2024 Eurotech and/or its affiliates and others
#
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#  Eurotech
#

backup_files() {
    SUFFIX="${1}"

    shift

    for file in "${@}"
    do
        if [ -f "${file}" ]
        then
            mv "${file}" "${file}.${SUFFIX}"
        fi
    done
}

IS_NETWORKING_PROFILE=false
INSTALL_DIR=/opt/eclipse

# create known kura install location
ln -sf ${INSTALL_DIR}/kura_* ${INSTALL_DIR}/kura

# set up kura init
sed "s|INSTALL_DIR|${INSTALL_DIR}|" ${INSTALL_DIR}/kura/install/kura.service > /lib/systemd/system/kura.service
systemctl daemon-reload
systemctl enable kura
chmod +x ${INSTALL_DIR}/kura/bin/*.sh

# setup snapshot_0 recovery folder
if [ ! -d ${INSTALL_DIR}/kura/.data ]; then
    mkdir ${INSTALL_DIR}/kura/.data
fi

mkdir -p ${INSTALL_DIR}/kura/data

# manage running services
systemctl daemon-reload
systemctl stop systemd-timesyncd
systemctl disable systemd-timesyncd
systemctl stop chrony
systemctl disable chrony

# set up users and grant permissions
cp ${INSTALL_DIR}/kura/install/manage_kura_users.sh ${INSTALL_DIR}/kura/.data/manage_kura_users.sh
chmod 700 ${INSTALL_DIR}/kura/.data/manage_kura_users.sh
${INSTALL_DIR}/kura/.data/manage_kura_users.sh -i -nn

bash "${INSTALL_DIR}/kura/install/customize-installation.sh"${IS_NETWORKING_PROFILE}

# copy snapshot_0.xml
cp ${INSTALL_DIR}/kura/user/snapshots/snapshot_0.xml ${INSTALL_DIR}/kura/.data/snapshot_0.xml

# disable NTP service
if command -v timedatectl > /dev/null ;
  then
    timedatectl set-ntp false
fi

# set up logrotate - no need to restart as it is a cronjob
cp ${INSTALL_DIR}/kura/install/kura.logrotate /etc/logrotate-kura.conf

if [ ! -f /etc/cron.d/logrotate-kura ]; then
    test -d /etc/cron.d || mkdir -p /etc/cron.d
    touch /etc/cron.d/logrotate-kura
    echo "*/5 * * * * root /usr/sbin/logrotate --state /var/log/logrotate-kura.status /etc/logrotate-kura.conf" >> /etc/cron.d/logrotate-kura
fi

# set up systemd-tmpfiles
cp ${INSTALL_DIR}/kura/install/kura-tmpfiles.conf /etc/tmpfiles.d/kura.conf

# set up kura files permissions
chmod 700 ${INSTALL_DIR}/kura/bin/*.sh
chown -R kurad:kurad /opt/eclipse
chmod -R go-rwx /opt/eclipse
chmod a+rx /opt/eclipse
find /opt/eclipse/kura -type d -exec chmod u+x "{}" \;

keytool -genkey -alias localhost -keyalg RSA -keysize 2048 -keystore /opt/eclipse/kura/user/security/httpskeystore.ks -deststoretype pkcs12 -dname "CN=Kura, OU=Kura, O=Eclipse Foundation, L=Ottawa, S=Ontario, C=CA" -ext ku=digitalSignature,nonRepudiation,keyEncipherment,dataEncipherment,keyAgreement,keyCertSign -ext eku=serverAuth,clientAuth,codeSigning,timeStamping -validity 1000 -storepass changeit -keypass changeit
