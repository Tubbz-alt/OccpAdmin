class occp::sslremove {
  $sslDir = '/var/lib/puppet/ssl'
  file { $sslDir:
    ensure => absent,
    force  => true,
    backup => false,
  }
}
