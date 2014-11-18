class occpadmin::update (
  $local_user      = $occpadmin::params::local_user,
  $local_user_home = $occpadmin::params::local_user_home,
  $occp_source_dir = $occpadmin::params::occp_source_dir,
  $occp_hidden_dir = $occpadmin::params::occp_hidden_dir,
  $occp_bin_dir    = $occpadmin::params::occp_bin_dir,
  $hostname        = $occpadmin::params::hostname,
) inherits occpadmin::params {
  # Write the version file
  file { '/etc/occp-vm-release':
    ensure => file,
    owner  => 'root',
    group  => 'root',
    mode   => '0644',
    source => "puppet:///modules/${module_name}/occp-vm-release",
  }
  # rebuild the OCCP Admin Program
  exec { 'build':
    command => '/usr/bin/ant',
    cwd     => "${occp_source_dir}",
    user    => "${local_user}",
    # It is assumed that the source is in place and ant is installed
  }
}
