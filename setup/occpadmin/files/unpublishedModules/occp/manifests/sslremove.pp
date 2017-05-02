class occp::sslremove {
  $sslDir = $settings::ssldir
  file { $sslDir:
    ensure => absent,
    force  => true,
    backup => false,
  }
}
