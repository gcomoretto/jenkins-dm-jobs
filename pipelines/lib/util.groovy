import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/**
 * Remove leading whitespace from a multi-line String (probably a shellscript).
 */
@NonCPS
def String dedent(String text) {
  if (text == null) {
    return null
  }
  text.replaceFirst("\n","").stripIndent()
}

/**
 * Thin wrapper around {@code sh} step that strips leading whitspace.
 */
def void posixSh(script) {
  script = dedent(script)
  sh shebangerize(script, '/bin/sh -xe')
}

/**
 * Thin wrapper around {@code sh} step that strips leading whitspace.
 */
def void bash(script) {
  script = dedent(script)
  sh shebangerize(script, '/bin/bash -xe')
}

/**
 * Prepend a shebang to a String that does not already have one.
 *
 * @param script String Text to prepend a shebang to
 * @return shebangerized String
 */
@NonCPS
def String shebangerize(String script, String prog = '/bin/sh -xe') {
  if (!script.startsWith('#!')) {
    script = "#!${prog}\n${script}"
  }

  script
}
/**
 * Build a docker image, constructing the `Dockerfile` from `config`.
 *
 * @param config String literal text of Dockerfile
 * @param tag String name of tag to apply to generated image
 */
def void buildImage(String config, String tag) {
  writeFile(file: 'Dockerfile', text: config)

  bash """
    docker build -t "${tag}" \
        --build-arg D_USER="\$(id -un)" \
        --build-arg D_UID="\$(id -u)" \
        --build-arg D_GROUP="\$(id -gn)" \
        --build-arg D_GID="\$(id -g)" \
        --build-arg D_HOME="\$HOME" \
        .
  """
}

/**
 * Create a thin "wrapper" container around {@code imageName} to map uid/gid of
 * the user invoking docker into the container.
 *
 * @param imageName docker image slug
 * @param tag name of tag to apply to generated image
 */
def void wrapContainer(String imageName, String tag) {
  def buildDir = 'docker'
  def config = dedent("""
    FROM    ${imageName}

    ARG     D_USER
    ARG     D_UID
    ARG     D_GROUP
    ARG     D_GID
    ARG     D_HOME

    USER    root
    RUN     mkdir -p "\$(dirname \$D_HOME)"
    RUN     groupadd -g \$D_GID \$D_GROUP
    RUN     useradd -d \$D_HOME -g \$D_GROUP -u \$D_UID \$D_USER

    USER    \$D_USER
    WORKDIR \$D_HOME
  """)

  // docker insists on recusrively checking file access under its execution
  // path -- so run it from a dedicated dir
  dir(buildDir) {
    buildImage(config, tag)

    deleteDir()
  }
}

/**
 * Invoke block inside of a "wrapper" container.  See: wrapContainer
 *
 * @param docImage String name of docker image
 * @param run Closure Invoked inside of wrapper container
 */
def insideWrap(String docImage, String args=null, Closure run) {
  def docLocal = "${docImage}-local"

  wrapContainer(docImage, docLocal)
  def image = docker.image(docLocal)

  image.inside(args) { run() }
}

/**
 * Join multiple String args togther with '/'s to resemble a filesystem path.
 */
// The groovy String#join method is not working under the security sandbox
// https://issues.jenkins-ci.org/browse/JENKINS-43484
@NonCPS
def String joinPath(String ... parts) {
  String text = null

  def n = parts.size()
  parts.eachWithIndex { x, i ->
    if (text == null) {
      text = x
    } else {
      text += x
    }

    if (i < (n - 1)) {
      text += '/'
    }
  }

  return text
}

/**
 * Serialize a Map to a JSON string and write it to a file.
 *
 * @param filename output filename
 * @param data Map to serialize
 */
@NonCPS
def dumpJson(String filename, Map data) {
  def json = new groovy.json.JsonBuilder(data)
  def pretty = groovy.json.JsonOutput.prettyPrint(json.toString())
  echo pretty
  writeFile file: filename, text: pretty
}

/**
 * Serialize a Map to a JSON string and write it to a file.
 *
 * @param filename output filename
 * @param data Map to serialize
 * @return LazyMap parsed JSON object
 */
@NonCPS
def slurpJson(String data) {
  def slurper = new groovy.json.JsonSlurper()
  slurper.parseText(data)
}


/**
 * Create an EUPS distrib tag
 *
 * @param buildId bNNNN
 * @param eupsTag tag name
 * @param product whitespace delimited string of products to tag
 * @param publishJob job to trigger (does the actual work)
 * @param timelimit Integer build timeout in hours
 */
def tagProduct(
  String buildId,
  String eupsTag,
  String product,
  String publishJob = 'release/run-publish',
  Integer timelimit = 1
) {
  build job: publishJob,
    parameters: [
      string(name: 'EUPSPKG_SOURCE', value: 'git'),
      string(name: 'BUILD_ID', value: buildId),
      string(name: 'TAG', value: eupsTag),
      string(name: 'PRODUCT', value: product),
      string(name: 'TIMEOUT', value: timelimit.toString()), // hours
    ]
}

/**
 * Run a lsstsw build.
 *
 * @param image String
 * @param label Node label to run on
 * @param compiler String compiler to require and setup, if nessicary.
 * @param python Python major revsion to build with. Eg., '2' or '3'
 * @param wipteout Delete all existing state before starting build
 */
def lsstswBuild(
  String image,
  String label,
  String compiler,
  String python,
  String slug,
  Boolean wipeout=false
) {
  def run = {
    withEnv([
      'SKIP_DOCS=true',
      "LSST_JUNIT_PREFIX=${slug}",
      "LSST_PYTHON_VERSION=${python}",
      "LSST_COMPILER=${compiler}",
    ]) {
      jenkinsWrapper()
    }
  } // run

  def runDocker = {
    insideWrap(image) {
      run()
    }
  } // runDocker

  def runEnv = { doRun ->
    timeout(time: 8, unit: 'HOURS') {
      // use different workspace dirs for python 2/3 to avoid residual state
      // conflicts
      try {
        dir(slug) {
          if (wipeout) {
            deleteDir()
          }

          doRun()
        } // dir
      } finally {
        // needs to be called in the parent dir of jenkinsWrapper() in order to
        // add the slug as a prefix to the archived files.
        jenkinsWrapperPost(slug)
      }
    } // timeout
  } // runEnv

  def agent = null
  def task = null
  if (image) {
    agent = 'docker'
    task = { runEnv(runDocker) }
  } else {
    agent = label
    task = { runEnv(run) }
  }

  node(agent) {
    task()
  } // node
}

/**
 * Run a jenkins_wrapper.sh
 */
def jenkinsWrapper() {
  def cwd = pwd()

  try {
    dir('lsstsw') {
      cloneLsstsw()
    }

    dir('ci-scripts') {
      cloneCiScripts()
    }

    // workspace relative dir for dot files to prevent bleed through between
    // jobs and subsequent builds.
    dir('home') {
      deleteDir()

      // this is a lazy way to recreate the directory
      writeFile(file: '.dummy', text: '')
    }

    // cleanup *all* conda cached package info
    [
      'lsstsw/miniconda/conda-meta',
      'lsstsw/miniconda/pkgs',
    ].each { it ->
      dir(it) {
        deleteDir()
      }
    }

    withCredentials([[
      $class: 'StringBinding',
      credentialsId: 'cmirror-s3-bucket',
      variable: 'CMIRROR_S3_BUCKET'
    ]]) {
      withEnv([
        "WORKSPACE=${cwd}",
        "HOME=${cwd}/home",
        "EUPS_USERDATA=${cwd}/home/.eups_userdata",
      ]) {
        bash './ci-scripts/jenkins_wrapper.sh'
      }
    } // withCredentials([[
  } finally {
    withEnv(["WORKSPACE=${cwd}"]) {
      bash '''
        if hash lsof 2>/dev/null; then
          Z=$(lsof -d 200 -t)
          if [[ ! -z $Z ]]; then
            kill -9 $Z
          fi
        else
          echo "lsof is missing; unable to kill rebuild related processes."
        fi

        rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
      '''
    }
  } // try
} // jenkinsWrapper

def jenkinsWrapperPost(String baseDir = null) {
  def lsstsw = 'lsstsw'

  if (baseDir) {
    lsstsw = "${baseDir}/${lsstsw}"
  }

  // note that archive does not like a leading `./`
  def lsstsw_build_dir = "${lsstsw}/build"
  def manifestPath = "${lsstsw_build_dir}/manifest.txt"
  def statusPath = "${lsstsw_build_dir}/status.yaml"
  def archive = [
    manifestPath,
    statusPath,
  ]
  def record = [
    '*.log',
    '*.failed',
  ]

  try {
    if (fileExists(statusPath)) {
      def status = readYaml(file: statusPath)

      def products = status['built']
      // if there is a "failed_at" product, check it for a junit file too
      if (status['failed_at']) {
        products << status['failed_at']
      }

      def reports = []
      products.each { item ->
        def name = item['name']
        def xml = "${lsstsw_build_dir}/${name}/tests/.tests/pytest-${name}.xml"
        reports << xml

        record.each { pattern ->
          archive += "${lsstsw_build_dir}/${name}/**/${pattern}"
        }
      }

      if (reports) {
        // note that junit will ignore files with timestamps before the start
        // of the build
        junit([
          testResults: reports.join(', '),
          allowEmptyResults: true,
        ])

        archive += reports
      }
    }
  } catch (e) {
    // As a last resort, find product build dirs with a wildcard.  This might
    // match logs for products that _are not_ part of the current build.
    record.each { pattern ->
      archive += "${lsstsw_build_dir}/**/${pattern}"
    }
    throw e
  } finally {
    archiveArtifacts([
      artifacts: archive.join(', '),
      allowEmptyArchive: true,
      fingerprint: true
    ])
  } // try
} // jenkinsWrapperPost

/**
 * Parse bNNNN out of a manifest.txt format String.
 *
 * @param manifest.txt as a String
 * @return String
 */
@NonCPS
def String bxxxx(String manifest) {
  def m = manifest =~ /(?m)^BUILD=(b.*)/
  m ? m[0][1] : null
}

/**
 * Validate that required parameters were passed from the job and raise an
 * error on any that are missing.
 *
 * @param rps List of required job parameters
 */
def void requireParams(List rps) {
  rps.each { it ->
    if (params.get(it) == null) {
      error "${it} parameter is required"
    }
  }
}

/**
 * Validate that required env vars were passed from the job and raise an
 * error on any that are missing.
 *
 * @param rev List of required env vars
 */
def void requireEnvVars(List rev) {
  // note that `env` isn't a map and #get doesn't work as expected
  rev.each { it ->
    if (env."${it}" == null) {
      error "${it} envirnoment variable is required"
    }
  }
}

/**
 * Empty directories by deleting and recreating them.
 *
 * @param dirs List of directories to empty
*/
def void emptyDirs(List eds) {
  eds.each { d ->
    dir(d) {
      deleteDir()
      // a file operation is needed to cause the dir() step to recreate the dir
      writeFile(file: '.dummy', text: '')
    }
  }
}

/**
 * XXX this method was developed during the validate_drp conversion to pipeline
 * but is currently unusued.  It has been preserved as it might be useful in
 * other jobs.
 *
 * Write a copy of `manifest.txt`.
 *
 * @param rebuildId String `run-rebuild` build id.
 * @param filename String Output filename.
 */
def void getManifest(String rebuildId, String filename) {
  def manifest_artifact = 'lsstsw/build/manifest.txt'
  def buildJob          = 'release/run-rebuild'

  step([$class: 'CopyArtifact',
        projectName: buildJob,
        filter: manifest_artifact,
        selector: [
          $class: 'SpecificBuildSelector',
          buildNumber: rebuildId // wants a string
        ],
      ])

  def manifest = readFile manifest_artifact
  writeFile(file: filename, text: manifest)
}

/**
 * Run the `github-tag-version` script from `sqre-codekit` with parameters.
 *
 * @param gitTag String name of git tag to create
 * @param buildId String bNNNN/manifest id to select repos/refs to tag
 * @param options Map see `makeCliCmd`
 */
def void githubTagVersion(String gitTag, String buildId, Map options) {
  def prog = 'github-tag-version'
  def defaultOptions = [
    '--dry-run': true,
    '--org': 'lsst',
    '--team': 'Data Management',
    '--email': 'sqre-admin@lists.lsst.org',
    '--user': 'sqreadmin',
    '--token':  '$GITHUB_TOKEN',
    '--fail-fast': true,
    '--debug': true,
  ]

  runCodekitCmd(prog, defaultOptions, options, [gitTag, buildId])
} // githubTagVersion

/**
 * Run the `github-tag-teams` script from `sqre-codekit` with parameters.
 *
 * @param options Map see `makeCliCmd`
 */
def void githubTagTeams(Map options) {
  def prog = 'github-tag-teams'
  def defaultOptions = [
    '--dry-run': true,
    '--org': 'lsst',
    '--team': 'Data Management',
    '--email': 'sqre-admin@lists.lsst.org',
    '--user': 'sqreadmin',
    '--token':  '$GITHUB_TOKEN',
    '--debug': true,
  ]

  runCodekitCmd(prog, defaultOptions, options, null)
} // githubTagVersion

/**
 * Run a codekit cli command.
 *
 * @param prog String see `makeCliCmd`
 * @param defaultOptions Map see `makeCliCmd`
 * @param options Map see `makeCliCmd`
 * @param args List see `makeCliCmd`
 */
def void runCodekitCmd(
  String prog,
  Map defaultOptions,
  Map options,
  List args,
  Integer timelimit = 30
) {
  def cliCmd = makeCliCmd(prog, defaultOptions, options, args)

  timeout(time: timelimit, unit: 'MINUTES') {
    insideCodekit {
      bash cliCmd
    }
  }
} // runCodekitCmd

/**
 * Generate a string for executing a system command with optional flags and/or
 * arguments.
 *
 * @param prog String command to run.
 * @param defaultOptions Map command option flags.
 * @param options Map script option flags.  These are merged with
 * defaultOptions.  Truthy values are considered as an active flag while the
 * literal `true` constant indicates a boolean flag.  Falsey values result in
 * the flag being omitted.  Lists/Arrays result in the flag being specified
 * multiple times.
 * @param args List verbatium arguments to pass to command.
 * @return String complete cli command
 */
def String makeCliCmd(
  String prog,
  Map defaultOptions,
  Map options,
  List args
) {
  def useOpts = [:]

  if (defaultOptions) {
    useOpts = defaultOptions
  }
  if (options) {
    useOpts += options
  }

  cmd = [prog]

  if (useOpts) {
    cmd += mapToCliFlags(useOpts)
  }
  if (args) {
    cmd += listToCliArgs(args)
  }

  return cmd.join(' ')
} // makeCliCmd

/**
 * Run block inside a container with sqre-codekit installed and a github oauth
 * token defined as `GITHUB_TOKEN`.
 *
 * @param run Closure Invoked inside of node step
 */
def void insideCodekit(Closure run) {
  def docImage  = 'lsstsqre/codekit:6.0.0'

  insideWrap(docImage) {
    withGithubAdminCredentials {
      run()
    } // withGithubAdminCredentials
  } // insideWrap
}

/**
 * Convert a map of command line flags (keys) and values into a string suitable
 * to be passed on "the cli" to a program
 *
 * @param opt Map script option flags
 */
def String mapToCliFlags(Map opt) {
  def flags = []

  opt.each { k,v ->
    if (v) {
      if (v == true) {
        // its a boolean flag
        flags += k
      } else {
        // its a flag with an arg
        if (v instanceof List) {
          // its a flag with multiple values
          v.each { nested ->
            flags += "${k} \"${nested}\""
          }
        } else {
          // its a flag with a single value
          flags += "${k} \"${v}\""
        }
      }
    }
  }

  return flags.join(' ')
}

/**
 * Convert a List of command line args into a string suitable
 * to be passed on "the cli" to a program
 *
 * @param args List of command arguments
 * @return String of arguments
 */
def String listToCliArgs(List args) {
  return args.collect { "\"${it}\"" }.join(' ')
}

/**
 * Run block with a github oauth token defined as `GITHUB_TOKEN`.
 *
 * @param run Closure Invoked inside of node step
 */
def void withGithubAdminCredentials(Closure run) {
  withCredentials([[
    $class: 'StringBinding',
    credentialsId: 'github-api-token-sqreadmin',
    variable: 'GITHUB_TOKEN'
  ]]) {
    run()
  } // withCredentials
}

/**
 * Run trivial execution time block
 *
 * @param run Closure Invoked inside of node step
 */
def void nodeTiny(Closure run) {
  node('jenkins-master') {
    timeout(time: 5, unit: 'MINUTES') {
      run()
    }
  }
}

/**
 * Execute a multiple multiple lsstsw builds using different configurations.
 *
 * @param config List of lsstsw build configurations
 * @param wipeout Boolean wipeout the workspace build starting the build
 */
def lsstswBuildMatrix(List lsstswConfigs, Boolean wipeout=false) {
  def matrix = [:]

  // XXX validate config
  lsstswConfigs.each { item ->
    def displayName = item.display_name ?: item.label
    def displayCompiler = item.display_compiler ?: item.compiler
    def slug = "${displayName}.${displayCompiler}.py${item.python}"

    matrix[slug] = {
      lsstswBuild(
        item.image,
        item.label,
        item.compiler,
        item.python,
        slug,
        wipeout
      )
    }
  }

  parallel matrix
}

/**
 * Clone lsstsw git repo
 */
@NonCPS
def void cloneLsstsw() {
  gitNoNoise(
    url: 'https://github.com/lsst/lsstsw.git',
    branch: 'master',
  )
}

/**
 * Clone ci-scripts git repo
 */
@NonCPS
def void cloneCiScripts() {
  gitNoNoise(
    url: 'https://github.com/lsst-sqre/ci-scripts.git',
    branch: 'master',
  )
}

/**
 * Clone git repo without generating a jenkins bulid changelog
 */
def void gitNoNoise(Map args) {
  git([
    url: args.url,
    branch: args.branch,
    changelog: false,
    poll: false
  ])
}

/**
 * Parse yaml file into object.
 *
 * @param file String file to parse
 */
def Object readYamlFile(String file) {
  readYaml(text: readFile(file))
}

def void buildTarballMatrix(
  Map config,
  String product,
  String eupsTag,
  Map opt,
  Integer retries = 3
) {
  def platform = [:]

  config['tarball'].each { item ->
    def displayName = item.display_name ?: item.label
    def displayCompiler = item.display_compiler ?: item.compiler

    def slug = "miniconda${item.python}"
    slug += "-${item.miniver}-${item.lsstsw_ref}"

    platform["${displayName}.${displayCompiler}.${slug}"] = {
      retry(retries) {
        build job: 'release/tarball',
          parameters: [
            string(name: 'PRODUCT', value: product),
            string(name: 'EUPS_TAG', value: eupsTag),
            booleanParam(name: 'SMOKE', value: opt.SMOKE),
            booleanParam(name: 'RUN_DEMO', value: opt.RUN_DEMO),
            booleanParam(name: 'RUN_SCONS_CHECK', value: opt.RUN_SCONS_CHECK),
            booleanParam(name: 'PUBLISH', value: opt.PUBLISH),
            booleanParam(name: 'WIPEOUT', value: false),
            string(name: 'TIMEOUT', value: '8'), // hours
            string(name: 'IMAGE', value: nullToEmpty(item.image)),
            string(name: 'LABEL', value: item.label),
            string(name: 'COMPILER', value: item.compiler),
            string(name: 'PYTHON_VERSION', value: item.python),
            string(name: 'MINIVER', value: item.miniver),
            string(name: 'LSSTSW_REF', value: item.lsstsw_ref),
          ]
      } // retry
    } // platform
  } // each

  parallel platform
}

/**
 * Convert null to empty string; pass through valid strings
 *
 * @param s String string to process
 */
@NonCPS
def String nullToEmpty(String s) {
  if (!s) { s = '' }
  s
}

/**
 * Convert an empty string to null; pass through valid strings
 *
 * @param s String string to process
 */
@NonCPS
def String emptyToNull(String s) {
  if (s == '') { s = null }
  s
}

/**
 * Convert UNIX epoch (seconds) to a UTC formatted date/time string.
 * @param epoch Integer count of seconds since UNIX epoch
 * @return String UTC formatted date/time string
 */
@NonCPS
def String epochToUtc(Integer epoch) {
  def unixTime = Instant.ofEpochSecond(epoch)
  instantToUtc(unixTime)
}

/**
 * Convert UNIX epoch (milliseconds) to a UTC formatted date/time string.
 * @param epoch Integer count of milliseconds since UNIX epoch
 * @return String UTC formatted date/time string
 */
@NonCPS
def String epochMilliToUtc(Long epoch) {
  def unixTime = Instant.ofEpochMilli(epoch)
  instantToUtc(unixTime)
}

/**
 * Convert java.time.Instant objects to a UTC formatted date/time string.
 * @param moment java.time.Instant object
 * @return String UTC formatted date/time string
 */
@NonCPS
def String instantToUtc(Instant moment) {
  def utcFormat = DateTimeFormatter
                    .ofPattern("yyyyMMdd'T'hhmmssX")
                    .withZone(ZoneId.of('UTC') )

  utcFormat.format(moment)
}

/**
 * Run librarian-puppet on the current directory via a container
 *
 * @param cmd String librarian-puppet arguments; defaults to 'install'
 * @param tag String tag of docker image to use.
 */
def void librarianPuppet(String cmd='install', String tag='2.2.3') {
  insideWrap("lsstsqre/cakepan:${tag}", "-e HOME=${pwd()}") {
    bash "librarian-puppet ${cmd}"
  }
}

/**
 * run documenteer doc build
 *
 * @param args.docTemplateDir String path to sphinx template clone (required)
 * @param args.eupsPath String path to EUPS installed productions (optional)
 * @param args.eupsTag String tag to setup. defaults to 'current'
 * @param args.docImage String defaults to: 'lsstsqre/documenteer-base'
 */
def runDocumenteer(Map args) {
  def argDefaults = [
    docImage: 'lsstsqre/documenteer-base',
    eupsTag: 'current',
  ]
  args = argDefaults + args

  def homeDir = "${pwd()}/home"
  emptyDirs([homeDir])

  def docEnv = [
    "HOME=${homeDir}",
    "EUPS_TAG=${args.eupsTag}",
  ]

  if (args.eupsPath) {
    docEnv += "EUPS_PATH=${args.eupsPath}"
  }

  withEnv(docEnv) {
    insideWrap(args.docImage) {
      dir(args.docTemplateDir) {
        bash '''
          source /opt/lsst/software/stack/loadLSST.bash
          export PATH="${HOME}/.local/bin:${PATH}"
          pip install --upgrade --user -r requirements.txt
          setup -r . -t "$EUPS_TAG"
          build-stack-docs -d . -v
        '''
      } // dir
    }
  } // withEnv
} // jenkinsWrapper

/**
 * run ltd-mason-travis to push a doc build
 *
 * @param args.eupsTag String tag to setup. Eg.: 'current', 'b1234'
 * @param args.repoSlug String github repo slug. Eg.: 'lsst/pipelines_lsst_io'
 * @param args.product String LTD product name., Eg.: 'pipelines'
 */
def ltdPush(Map args) {
  def masonImage = 'lsstsqre/ltd-mason'

  withEnv([
    "LTD_MASON_BUILD=true",
    "LTD_MASON_PRODUCT=${args.ltdProduct}",
    "LTD_KEEPER_URL=https://keeper.lsst.codes",
    "LTD_KEEPER_USER=travis",
    "TRAVIS_PULL_REQUEST=false",
    "TRAVIS_REPO_SLUG=${args.repoSlug}",
    "TRAVIS_BRANCH=${args.eupsTag}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'ltd-mason-aws',
      usernameVariable: 'LTD_MASON_AWS_ID',
      passwordVariable: 'LTD_MASON_AWS_SECRET',
    ],
    [
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'ltd-keeper',
      usernameVariable: 'LTD_KEEPER_USER',
      passwordVariable: 'LTD_KEEPER_PASSWORD',
    ]]) {
      docker.image(masonImage).inside {
        // expect that the service will return an HTTP 502, which causes
        // ltd-mason-travis to exit 1
        sh '''
        /usr/bin/ltd-mason-travis --html-dir _build/html --verbose || true
        '''
      } // insideWrap
    } // withCredentials
  } //withEnv
} // runLtdMason

/**
 * run `release/run-rebuild` job and parse result
 *
 * @param buildJob String job to trigger. Defaults to `release/rebuild`.
 * @param opts.BRANCH String
 * @param opts.PRODUCT String
 * @param opts.SKIP_DEMO Boolean Defaults to `false`.
 * @param opts.SKIP_DOCS Boolean Defaults to `false`.
 * @param opts.TIMEOUT String Defaults to `'8'`.
 * @return bxxxx String
 */
def String runRebuild(String buildJob='release/run-rebuild', Map opts) {
  def defaultOpts = [
    BRANCH: '',  // null is not a valid value for a string param
    PRODUCT: '',
    SKIP_DEMO: false,
    SKIP_DOCS: false,
    TIMEOUT: '8', // should be String
  ]
  def useOpts = defaultOpts + opts

  def result = build job: buildJob,
    parameters: [
      string(name: 'BRANCH', value: useOpts.BRANCH),
      string(name: 'PRODUCT', value: useOpts.PRODUCT),
      booleanParam(name: 'SKIP_DEMO', value: useOpts.SKIP_DEMO),
      booleanParam(name: 'SKIP_DOCS', value: useOpts.SKIP_DOCS),
      string(name: 'TIMEOUT', value: useOpts.TIMEOUT), // hours
    ],
    wait: true

  def bx = null
  nodeTiny {
    manifest_artifact = 'lsstsw/build/manifest.txt'

    step([$class: 'CopyArtifact',
          projectName: buildJob,
          filter: manifest_artifact,
          selector: [
            $class: 'SpecificBuildSelector',
            buildNumber: result.id,
          ],
        ])

    def manifest = readFile manifest_artifact
    bx = bxxxx(manifest)
    echo "parsed bxxxx: ${bx}"
  } // nodeTiny

  return bx
}

return this;
