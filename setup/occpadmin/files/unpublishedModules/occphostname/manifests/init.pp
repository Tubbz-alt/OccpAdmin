class occphostname (
  $hostname,
  $domain = undef,
  $current_hostname = 'basevm',
){
  if $::environment == 'hostname' {
    host { 'old hostname':
      name   => 'basevm',
      ensure => absent,
    }
    if $::osfamily == 'debian' {
      # Manage the host file to resolve
      if $domain != undef {
        host { 'host file':
          name         => "${hostname}.${domain}",
          ensure       => present,
          host_aliases => $hostname,
          ip           => '127.0.1.1',
          require      => Host['old hostname'],
        }
      } else {
        host { 'host file':
          name    => $hostname,
          ensure  => present,
          ip      => '127.0.1.1',
          require => Host['old hostname'],
        }
      }

      # Manage the hostname file
      file { 'hostname file':
        path    => '/etc/hostname',
        ensure  => file,
        owner   => 'root',
        group   => 'root',
        mode    => '0644',
        content => template("${module_name}/debian_hostname.erb"),
      }
      # Make hostname take affect
      case $::operatingsystem {
        'Ubuntu': {
          if $::lsbdistrelease == '12.04' {
            service { 'hostname':
              ensure  => running,
              require => [File['hostname file'], Host['host file']],
            }
          } else {
            exec { 'reload hostname':
              command       => "/usr/bin/hostnamectl set-hostname ${hostname}",
            }
          }
        }
        default: {
            exec { 'reload hostname default':
              command       => "/usr/bin/hostnamectl set-hostname ${hostname}",
            }
        }
      }

    } else {
      warning("${::osfamily} is not currently supported by ${module_name}, the hostname will not be set to ${hostname}!")
    }
  }
}
