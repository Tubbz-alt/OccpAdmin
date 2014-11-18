class occpadmin::params {
  # The local user's credentials
  # default occpadmin:0ccpadmin
  $local_user = 'occpadmin'
  $local_user_password = '$6$lbXSIvfN$yVs4Yopo1kneTvBC3YgsZ0hLI.tXCttLJn8zOc0IFCyXj76Rhxjc28eJnNHnu1woeMu0WWaO9Xr/q7P34svY00'

  ## Convenience variables
  # home directory
  $local_user_home = "/home/${local_user}/"
  # Admin program source directory
  $occp_source_dir = "${local_user_home}occp/source"
  # The OCCP "hidden" directory
  $occp_hidden_dir = "${local_user_home}occp/.occp"
  # The local user bin directory
  $occp_bin_dir = "${local_user_home}bin/"

  # Desired hostname
  $hostname = 'adminvm.occp.local'
}
