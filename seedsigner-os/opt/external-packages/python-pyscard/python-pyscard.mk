 ################################################################################
 #
 # python-pyscard
 #
 ################################################################################

PYTHON_PYSCARD_VERSION = 2.3.1
PYTHON_PYSCARD_SITE = $(call github,LudovicRousseau,pyscard,$(PYTHON_PYSCARD_VERSION))
PYTHON_PYSCARD_SETUP_TYPE = setuptools
PYTHON_PYSCARD_LICENSE = LGPL
PYTHON_PYSCARD_ASCII_ALIAS = /tmp/br-smartcard-runtime-ascii
# Ensure the swig host tool is available for wrapper generation
PYTHON_PYSCARD_DEPENDENCIES += host-swig pcsc-lite
# Explicitly point setup.py to the host-provided swig binary
PYTHON_PYSCARD_ENV += \
	SWIG="$(HOST_DIR)/bin/swig" \
	PKG_CONFIG="$(HOST_DIR)/bin/pkg-config" \
	PKG_CONFIG_SYSROOT_DIR="$(PYTHON_PYSCARD_ASCII_ALIAS)/host/arm-Buildroot-linux-gnueabihf/sysroot" \
	PKG_CONFIG_LIBDIR="$(PYTHON_PYSCARD_ASCII_ALIAS)/host/arm-Buildroot-linux-gnueabihf/sysroot/usr/lib/pkgconfig:$(PYTHON_PYSCARD_ASCII_ALIAS)/host/arm-Buildroot-linux-gnueabihf/sysroot/usr/share/pkgconfig"

define PYTHON_PYSCARD_CREATE_ASCII_ALIAS
	ln -sfn $(BASE_DIR) $(PYTHON_PYSCARD_ASCII_ALIAS)
endef

PYTHON_PYSCARD_PRE_BUILD_HOOKS += PYTHON_PYSCARD_CREATE_ASCII_ALIAS

 
$(eval $(python-package))
