#!/bin/bash
# This script can either:
# - Create an AdminVM from the Ubuntu 14.04 BaseVM
# - Update an existing AdminVM

# Find where we are located
# Referenced from http://stackoverflow.com/a/246128
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Flags
UNDERSTANDS=false
CLEAN_INSTALL=false

# Process the arguments
while getopts c flag ; do
  if [ "$flag" == "c" ]; then
    echo -e "User understands the impact of running this script. Continuing..."
    UNDERSTANDS=true
  fi
done

# Exit if we have not gotten confirmation of the warning
if ! $UNDERSTANDS ; then
  echo -e "This script should only be run on an Ubuntu 14.04 BaseVM, or a properly backed up Admin VM, as root. It will assume full control while enforcing the required configuration and could harm any other system. If you understand, run this script with -c to continue."
  exit 1
fi

# Is this an install or an update? Determine by the existence of version file
if [ ! -e "/etc/occp-vm-release" ]; then
  CLEAN_INSTALL=true
  echo -e "Installing Admin VM..."
else
  echo -e "Updating Admin VM..."
fi

# Make sure we are root
if [ "$(id -u)" != "0" ]; then
  echo -e "ERROR: This script must be run as root" 1>&2
  exit 1
fi

# Installs puppet modules
module_install(){
  if [ ! -e "/opt/puppetlabs/puppet/modules/$2" ]; then
    puppet module install --force $1
  fi
}

# Uses "which" to determine if a tool exists
toolExists(){
  which $* &>/dev/null 2>&1
}

# Make sure puppet is installed
if ! toolExists puppet; then
  echo -e "ERROR: Puppet must be installed. Are you sure this is a Base VM?" 1>&2
  exit 1
fi

# Install required modules
echo -e "Installing modules"
module_install example42-network network
module_install puppetlabs/concat concat
module_install puppetlabs/stdlib stdlib
module_install example42-stdmod stdmod
module_install puppetlabs/vcsrepo vcsrepo

# Choose the manifest
echo -e "Applying config"
if $CLEAN_INSTALL; then
  # Install manifest
  PUPPET_MANIFEST="include occpadmin"
else
  # Update manifest
  PUPPET_MANIFEST="include occpadmin::update"
fi

# Apply the manifest and log output
puppet apply --modulepath /opt/puppetlabs/puppet/modules/:$SCRIPT_DIR -e "$PUPPET_MANIFEST" 2>&1 | tee puppet.log

# Check puppet's exit code
PUPPETRET=${PIPESTATUS[0]}
# An exit code of 0 or 2 are considered successful, others are failures
if [ "$PUPPETRET" != "0" ] && [ "$PUPPETRET" != "2" ]; then
  echo "Puppet FAILED, please check puppet.log in $SCRIPT_DIR for details"
  exit 1
else
  # The install leaves behind a copied directory, we leave it to the user to remove to avoid unintended data loss
  if [ -e '/root/OccpAdmin' ]; then
    echo -e "You may wish to delete /root/OccpAdmin, it should have been copied to the user's home directory"
  fi
  echo "Puppet claims success, please reboot now. (Especially important for fresh installs)"
  exit 0
fi
