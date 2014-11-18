class occp::poweroff (
  $cleanup = undef,
){
  $sleepCommand = '/bin/sleep 10s'
  $poweroffCommand = '/sbin/poweroff'
  $puppetPackage = 'puppet-common'

  case $cleanup {
    'phase1': {
      require occp::sslremove
      $command = "(${sleepCommand}; ${poweroffCommand}) &"
    }
    'phase2': {
      $removalCommand = "apt-get --purge autoremove -y ${puppetPackage}"
      $command = "(${sleepCommand}; ${removalCommand} && ${poweroffCommand}) &"
    }
    default: {
      $command = "(${sleepCommand}; ${poweroffCommand}) &"
    }
  }
  exec { $command:
    provider => 'shell',
  }
}
