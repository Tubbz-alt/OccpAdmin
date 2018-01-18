# Open Cyber Challenge Platform Administration Utility
This program is part of the Open Cyber Challenge Platform (OCCP). It runs on the Admin VM to control hypervisor(s) being used with the OCCP. Initially developed by The University of Rhode Island's Digital Forensics and Cyber Security Center under funding from the National Science Foundation. Pull requests are welcomed.

For more information visit: https://www.opencyberchallenge.net

## Updating your Admin VM
1. Before starting, you should snapshot your Admin VM. This will allow you to easily revert if you encounter any problems.
1. From your Admin VM, navigate to the source directory. `cd ~/occp/source`
1. Pull the latest source code `git pull`
1. Run the updater `sudo ./setup/setup.sh -c`
1. Reboot `sudo reboot`
1. Confirm the update `occpadmin --version`

_Note: Once you are satisfied that the update was successful and everything is in working order, you may wish to delete the snapshot created the first step._

## Contributing
Since the Administration Utility and Admin VM are inherently linked, altering the utility may require configuration changes to the Admin VM. If that is the case, you should include changes to the occpadmin module in the setup folder as part of your pull request.

This project has adopted the branching model described on http://nvie.com/posts/a-successful-git-branching-model/

## Installation
**Stop!** This program is already installed in the OCCP Admin VM. Most users should visit the project's web site to download the Admin VM instead of trying to create their own. The following is only meant for platform developers that wish to make their own Admin VM.

Creating an Admin VM

1. Clone the Ubuntu 16.04 Server Base VM
1. Add a second NIC to the VM and attach it to your setup network
1. Login to the VM. The default credentials are root:0ccpadmin.
  1. You should update the VM now `apt-get update && apt-get dist-upgrade -y`
  1. Install git `apt-get install -y git`
  1. Clone this repository `git clone https://github.com/OpenCyberChallengePlatform/OccpAdmin.git`
  1. Run the installer `./OccpAdmin/setup/setup.sh -c`*
  1. Reboot the VM `reboot`
  1. Login with the new credentials (occpadmin:0ccpadmin) and remove the old repository `sudo rm -rf /root/OccpAdmin`
  1. You should change the password, but otherwise you should have an equivalent Admin VM to the one available from the project's website.

*_Note: Experienced users may discover the Puppet module has some parameters that are not exposed via the setup script. Changing those parameters should be done with caution since it will create an Admin VM that does not match published documentation._
