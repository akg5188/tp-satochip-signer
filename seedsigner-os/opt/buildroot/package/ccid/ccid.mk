################################################################################
#
# ccid
#
################################################################################

CCID_VERSION = 1.5.2
CCID_SOURCE = ccid-$(CCID_VERSION).tar.bz2
CCID_SITE = https://ccid.apdu.fr/files
CCID_LICENSE = LGPL-2.1+
CCID_LICENSE_FILES = COPYING
CCID_INSTALL_STAGING = YES
CCID_DEPENDENCIES = pcsc-lite host-pkgconf libusb
CCID_CONF_OPTS = --enable-usbdropdir=/usr/lib/pcsc/drivers

define CCID_FIX_PCSC_HEADERS
	ln -sf PCSC/ifdhandler.h $(STAGING_DIR)/usr/include/ifdhandler.h
	ln -sf PCSC/pcsclite.h $(STAGING_DIR)/usr/include/pcsclite.h
	ln -sf PCSC/reader.h $(STAGING_DIR)/usr/include/reader.h
	ln -sf PCSC/wintypes.h $(STAGING_DIR)/usr/include/wintypes.h
endef

CCID_PRE_CONFIGURE_HOOKS += CCID_FIX_PCSC_HEADERS

ifeq ($(BR2_PACKAGE_HAS_UDEV),y)
define CCID_INSTALL_UDEV_RULES
	if test -d $(TARGET_DIR)/etc/udev/rules.d ; then \
		cp $(@D)/src/92_pcscd_ccid.rules $(TARGET_DIR)/etc/udev/rules.d/ ; \
	fi;
endef

CCID_POST_INSTALL_TARGET_HOOKS += CCID_INSTALL_UDEV_RULES
endif

$(eval $(autotools-package))
