class occpadmin (
  $local_user      = $occpadmin::params::local_user,
  $local_user_home = $occpadmin::params::local_user_home,
  $occp_source_dir = $occpadmin::params::occp_source_dir,
  $occp_hidden_dir = $occpadmin::params::occp_hidden_dir,
  $occp_bin_dir    = $occpadmin::params::occp_bin_dir,
  $hostname        = $occpadmin::params::hostname,
) inherits occpadmin::params {

  # Ensure we are using a tested BaseVM, use others at your own risk
  if ! ($::operatingsystem == 'Ubuntu' and $::lsbdistrelease == '16.04') {
    fail('This is only intended for the Ubuntu 16.04 BaseVM, this operating system not supported.')
  }

  ##
  ## Install required packages
  ##
  # Ensure sources get updated first
  Exec['apt_update'] -> Package <| |>
  exec { 'apt_update': command => '/usr/bin/apt-get update' }

  # Set the default option to installed for Package resources
  Package { ensure => installed}
  # Note: we assume puppet is installed if we got this far
  $required_packages = [
    'ant',
    'bridge-utils',
    'curl',
    'dnsmasq',
    'git',
    'make',
    'maven',
    'ntp',
    'openjdk-8-jdk',
    'openjdk-8-jre-headless',
    'openvpn',
    'squid3',
    'vim',
    'virtualbox-guest-utils']
  # Install the array of packages
  package { $required_packages: }
  ##
  ## Puppet passenger setup
  ## (some voodoo requried)
  ##
  # Install passenger when the hostname is set properly
  package { 'puppetmaster-passenger':
    ensure  => installed,
    subscribe => File['/etc/hostname'],
  }
  # Now we need to run puppetmaster once, to acomplish this:
  # Stop apache then start the master (we assume the user will reboot after)
  service { 'apache2':
    ensure  => stopped,
    enable  => true,
    subscribe => Package['puppetmaster-passenger'],
  }
  # Run the puppet master once, after the hostname is set and it isn't running 
  # under passenger.
  exec { '/usr/bin/puppet master':
    subscribe => [Service['apache2'], Service['hostname']],
  }

  ##
  ## Configure the services
  ##
  # Stop dnsmasq and ensure it is not started at boot
  service { 'dnsmasq':
    ensure  => stopped,
    enable => false,
    require => Package['dnsmasq'],
  }
  # Stop NTP for now, but have it run on boot
  service { 'ntp':
    ensure  => stopped,
    enable  => true,
    require => File['/etc/ntp.conf'],
  }
  # Confirm puppet agent is not running and will not start on boot 
  service { 'puppet':
    ensure  => stopped,
    enable => false,
    #require => Package['puppet'], # assumed to be installed
  }

  ##
  ## Configure the hostname
  ##
  # Write hostname file
  file { '/etc/hostname':
    ensure  => file,
    content => "${hostname}",
    notify => Exec['reload_hostname'],
  }
  # Write hostname to hosts file
  file { '/etc/hosts':
    ensure  => file,
    content => template("${module_name}/hosts.erb"),
    notify  => File['/etc/hostname'],
  }
  # Make hostname take affect
  exec { 'reload_hostname':
    command     => "hostnamectl set-hostname ${hostname}",
    subscribe => File['/etc/hostname'],
  }

  ##
  ## Configure UFW for NAT routing
  ##
  # Write UFW config
  file { '/etc/default/ufw':
    ensure => file,
    owner  => 'root',
    group  => 'root',
    mode   => 0644,
    source => "puppet:///modules/${module_name}/ufw",
  }
  # Write UFW rules
  file { '/etc/ufw/before.rules':
    ensure  => file,
    owner   => 'root',
    group   => 'root',
    mode    => 0644,
    source  => "puppet:///modules/${module_name}/before.rules",
    require => File['/etc/default/ufw'],
  }
  # Write sysctl config & notify it to update
  file { '/etc/sysctl.conf':
    ensure  => file,
    owner   => 'root',
    group   => 'root',
    mode    => 0644,
    source  => "puppet:///modules/${module_name}/sysctl.conf",
    require => File['/etc/ufw/before.rules'],
    notify  => Exec['/sbin/sysctl -p'],
  }
  # Load sysctl changes
  exec { '/sbin/sysctl -p':
    refreshonly => true,
    notify      => Exec['reload_ufw'],
  }
  # Reload UFW
  exec { 'reload_ufw':
    command     => '/usr/sbin/ufw disable ; yes | /usr/sbin/ufw enable',
    refreshonly => true,
  }

  ##
  ## Configure Users
  ##
  # Create user account
  user { "${local_user}":
    ensure     => present,
    managehome => true,
    membership => 'minimum',
    groups     => ['sudo','vboxsf'],
    shell      => '/bin/bash',
    password   => "${local_user_password}",
    require    => Package['virtualbox-guest-utils'],
  }
  # Disable root account once we've created the local user
  user { 'root':  
    password => '*', 
    require => User["${local_user}"],
  }
  # Add puppet to the vboxsf group, assumes puppet is installed
  user { 'puppet':
    ensure     => present,
    membership => 'minimum',
    groups     => ['vboxsf'],
    require    => Package['virtualbox-guest-utils'],
  }

  ##
  ## Setup vim
  ##
  $vim_directories = [ "${local_user_home}.vim",
    "${local_user_home}.vim/autoload",
    "${local_user_home}.vim/bundle"]
  file { $vim_directories:
    ensure => directory,
    owner  => "${local_user}",
    group  => "${local_user}",
    require => User["${local_user}"],
  }
  # Copy the .vimrc
  file { "${local_user_home}.vimrc":
    ensure  => present,
    owner   => "${local_user}",
    group   => "${local_user}",
    source  => "puppet:///modules/${module_name}/vimrc",
    require => File["${local_user_home}.vim"],
  }
  # Install pathogen for vim
  exec { 'pathogenInstall':
    command => "/usr/bin/curl -SsLo ${local_user_home}.vim/autoload/pathogen.vim https://raw.githubusercontent.com/tpope/vim-pathogen/master/autoload/pathogen.vim",
    user    => "${local_user}",
    creates => "${local_user_home}.vim/autoload/pathogen.vim",
    require => File["${local_user_home}.vim/autoload"],
  }
  # Install vim-puppet for vim
  vcsrepo { "vim-puppet":
    ensure   => present,
    provider => git,
    source   => 'https://github.com/rodjek/vim-puppet.git',
    user     => "${local_user}",
    path     => "${local_user_home}.vim/bundle/vim-puppet",
    require  => File["${local_user_home}.vim/bundle"],
  }
  # Install tabular for vim
  vcsrepo { "tabular":
    ensure   => present,
    provider => git,
    source   => 'https://github.com/godlygeek/tabular.git',
    user     => "${local_user}",
    path     => "${local_user_home}.vim/bundle/tabular",
    require  => File["${local_user_home}.vim/bundle"],
  }

  ##
  ## Install the occpadmin program
  ##
  file { "${occp_source_dir}":
    ensure  => present,
    recurse => true,
    source  => '/root/OccpAdmin',
    owner   => "${local_user}",
    group   => "${local_user}",
  }
  
  # Clones the latest OCCP Admin Program source code
  #vcsrepo { "${occp_source_dir}":
  #    ensure   => present,
  #    provider => git,
  #    source   => 'https://github.com/OpenCyberChallengePlatform/OccpAdmin.git',
  #    user     => "${local_user}",
  #    require => User["${local_user}"],
  #}
  # Write .logging.properties
  file { "${local_user_home}occp/.logging.properties":
    ensure  => file,
    owner   => "${local_user}",
    group   => "${local_user}",
    content => template("${module_name}/logging.properties.erb"),
    mode    => '0644',
    require => File["${local_user_home}occp"],
  }
  # Write generic .occp.conf to avoid warning
  file { "${local_user_home}.occp.conf":
    ensure  => file,
    owner   => "${local_user}",
    group   => "${local_user}",
    content => 'setupNetworkName=OCCP_Setup',
    mode    => '0644',
    require => User["${local_user}"],
  }
  # Write wrapper script for running the OCCP Admin Program
  file { "${occp_bin_dir}occpadmin":
    ensure  => file,
    owner   => "${local_user}",
    group   => "${local_user}",
    source  => "puppet:///modules/${module_name}/occpadmin.sh",
    mode    => '0755',
    require => File["${occp_bin_dir}"],
  }
  # Build the OCCP Admin Program
  exec { 'build':
    command => '/usr/bin/ant',
    cwd     => "${occp_source_dir}",
    user    => "${local_user}",
    require => [File["${occp_source_dir}"], Package['ant'], Package['make']],
  }
  ##
  ## Configure Puppet
  ##
  # Configure master to serve the report directory
  file { '/etc/puppet/fileserver.conf':
    ensure  => file,
    owner   => 'root',
    group   => 'root',
    mode    => '0644',
    content => template("${module_name}/fileserver.conf.erb"),
    require => Package["puppetmaster-passenger"],
  }
  # Configure master to autosign certs
  file { "/etc/puppet/autosign.conf":
    ensure  => file,
    content => '*',
    require => Package["puppetmaster-passenger"],
  }
  # Configure master to use OCCP Admin Program generated nodes.pp
  file { "/etc/puppet/manifests/site.pp":
    ensure  => file,
    content => "import '${occp_hidden_dir}/nodes.pp'",
    require => Package["puppetmaster-passenger"],
  }
  # Place puppet config file
  file { "/etc/puppet/puppet.conf":
    ensure  => file,
    source  => "puppet:///modules/${module_name}/puppet.conf",
    require => Package["puppetmaster-passenger"],
  }
  # Copy OCCP Admin Program expected modules that aren't on the forge
  file { "/etc/puppet/modules":
    ensure  => directory,
    recurse => true,
    force   => true,
    source  => "puppet:///modules/${module_name}/unpublishedModules/",
    sourceselect => all,
    require => Package["puppetmaster-passenger"],
  }

  ##
  ## Additional Configurations
  ##
  file { '/etc/issue':
    ensure => file,
    source => "puppet:///modules/${module_name}/issue",
    owner  => 'root',
    group  => 'root',
  }
  file { '/etc/issue.net':
    ensure  => file,
    source  => "puppet:///modules/${module_name}/issue.net",
    owner  => 'root',
    group  => 'root',
  }
  # Adds script to display IP Address on login screen
  file { '/etc/network/if-up.d/show-ip-address': 
    ensure => file,
    source => "puppet:///modules/${module_name}/show-ip-address",
    mode   => 0755,
    owner  => 'root',
    group  => 'root',
  }
  # Replace the ssh server config
  file { '/etc/ssh/sshd_config':
    ensure => file,
    source => "puppet:///modules/${module_name}/sshd_config",
  }
  # Write the sudoers file for the OCCP Admin Program
  file { '/etc/sudoers':
    ensure  => file,
    owner   => 'root',
    group   => 'root',
    mode    => 0440,
    content => template("${module_name}/sudoers.erb"),
  }
  # OCCP Directories
  $occp_directories = [
    "${local_user_home}occp",
    "${local_user_home}occp/logs",
    "${occp_hidden_dir}",
    "${occp_hidden_dir}/modules",
    "${occp_hidden_dir}/OCCPReports",
    "${occp_bin_dir}"]
  file { $occp_directories:
    ensure  => directory,
    owner   => "${local_user}",
    group   => "${local_user}",
    require => User["${local_user}"],
  }
  # Place the VPN ISO
  file { "${occp_hidden_dir}/vpn.iso":
    ensure  => file,
    owner   => "${local_user}",
    group   => "${local_user}",
    source  => "puppet:///modules/${module_name}/vpn.iso",
    mode    => '0644',
    require => File["${occp_hidden_dir}"],
  }
  # Place the router ISO
  file { "${occp_hidden_dir}/router.iso":
    ensure  => file,
    owner   => "${local_user}",
    group   => "${local_user}",
    source  => "puppet:///modules/${module_name}/router.iso",
    mode    => '0644',
    require => File["${occp_hidden_dir}"],
  }
  # Write the Squid config file
  file { '/etc/squid3/squid.conf':
    ensure => file,
    owner  => 'root',
    group  => 'root',
    mode   => '0644',
    source  => "puppet:///modules/${module_name}/squid.conf",
    require => Package['squid3'],
  }
  # Write the NTP config file
  file { '/etc/ntp.conf':
    ensure  => file,
    owner   => 'root',
    group   => 'root',
    mode    => '0644',
    source  => "puppet:///modules/${module_name}/ntp.conf",
    require => Package['ntp'],
  }
  # Write the AdminVM version file
  file { '/etc/occp-vm-release':
    ensure => file,
    owner  => 'root',
    group  => 'root',
    mode   => '0644',
    source => "puppet:///modules/${module_name}/occp-vm-release",
  }
  # Adds the OCCP bin directory to the PATH
  file { '/etc/profile.d/homebinpath.sh':
    content => 'PATH=$HOME/bin/:$PATH',
  }
  # Place eclipse settings
  $eclipse_settings_directories = [
   "${local_user_home}workspace/",
   "${local_user_home}workspace/.metadata/",
   "${local_user_home}workspace/.metadata/.plugins/",
   "${local_user_home}workspace/.metadata/.plugins/org.eclipse.core.runtime/",
  ]
  file { $eclipse_settings_directories:
    ensure  => directory,
    owner   => "${local_user}",
    group   => "${local_user}",
    require => User["${local_user}"],
  }
  file { "${local_user_home}workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings":
    ensure  => directory,
    recurse => true,
    force   => true,
    owner   => "${local_user}",
    group   => "${local_user}",
    source  => "puppet:///modules/${module_name}/eclipse/",
    sourceselect => all,
    require => File["${local_user_home}workspace/.metadata/.plugins/org.eclipse.core.runtime/"],
  }

  ##
  ## Remove unwanted/no longer needed packages
  ##
  package { 'landscape-common': ensure => absent } #unwanted
}
