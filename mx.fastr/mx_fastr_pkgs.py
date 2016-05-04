#
# Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

from argparse import ArgumentParser
from os.path import join, exists, relpath, dirname
import shutil, os, re
import mx
import mx_fastr

def _mx_gnur():
    return mx.suite('gnur')

def _create_libinstall(s):
    '''Create lib.install.cran/install.tmp/test for suite s
    '''
    libinstall = join(s.dir, "lib.install.cran")
    # make sure its empty
    shutil.rmtree(libinstall, ignore_errors=True)
    os.mkdir(libinstall)
    install_tmp = join(s.dir, "install.tmp")
    shutil.rmtree(install_tmp, ignore_errors=True)
    test = join(s.dir, "test")
    shutil.rmtree(test, ignore_errors=True)
    os.mkdir(test)
    return libinstall, install_tmp

def _log_step(state, step, rvariant):
    print "{0} {1} with {2}".format(state, step, rvariant)

def pkgtest(args):
    '''used for package installation/testing'''
    parser = ArgumentParser(prog='r test')
    parser.add_argument('--ok-only', action='store_true', help='only install/test packages from the ok.packages file')
    parser.add_argument('--install-only', action='store_true', help='just install packages, do not test')
    # sundry options understood by installpkgs R code
    parser.add_argument('--pkg-count', action='store', help='number of packages to install/test', default='100')
    parser.add_argument('--pkg-filelist', action='store', help='pass --pkg-filelist')
    parser.add_argument('--ignore-blacklist', action='store_true', help='pass --ignore-blacklist')
    parser.add_argument('--install-dependents-first', action='store_true', help='pass -install-dependents-first')
    parser.add_argument('--print-ok-installs', action='store_true', help='pass --print-ok-installs')
    parser.add_argument('--invert-pkgset', action='store_true', help='pass --invert-pkgset')
    args = parser.parse_args(args)

    libinstall, install_tmp = _create_libinstall(mx.suite('fastr'))
    stacktrace_args = ['--J', '@-DR:-PrintErrorStacktracesToFile -DR:+PrintErrorStacktraces']

    install_args = []
    if args.pkg_count and not args.pkg_filelist:
        install_args += ['--pkg-count', args.pkg_count]
    if args.ok_only:
        # only install/test packages that have been successfully installed
        install_args += ['--pkg-filelist', join(mx_fastr._cran_test_project_dir(), 'ok.packages')]
    if args.pkg_filelist:
        install_args += ['--pkg-filelist', args.pkg_filelist]
    if not args.install_only:
        install_args += ['--run-tests']
    if args.ignore_blacklist:
        install_args += ['--ignore-blacklist']
    if args.install_dependents_first:
        install_args += ['--install-dependents-first']
    if args.print_ok_installs:
        install_args += ['--print-ok-installs']
    if args.invert_pkgset:
        install_args += ['--invert-pkgset']

    class OutputCapture:
        def __init__(self):
            self.install_data = None
            self.pkg = None
            self.mode = None
            self.start_install_pattern = re.compile(r"^BEGIN processing: (?P<package>[a-zA-Z0-9\.\-]+) .*")
            self.test_pattern = re.compile(r"^(?P<status>BEGIN|END) testing: (?P<package>[a-zA-Z0-9\.\-]+) .*")
            self.status_pattern = re.compile(r"^(?P<package>[a-zA-Z0-9\.\-]+): (?P<status>OK|FAILED).*")
            self.install_data = dict()
            self.install_status = dict()
            self.test_info = dict()

        def __call__(self, data):
            print data,
            if data == "BEGIN package installation\n":
                self.mode = "install"
                return
            elif data == "BEGIN install status\n":
                self.mode = "install_status"
                return
            elif data == "BEGIN package tests\n":
                self.mode = "test"
                return

            if self.mode == "install":
                start_install = re.match(self.start_install_pattern, data)
                if start_install:
                    pkg_name = start_install.group(1)
                    self.pkg = pkg_name
                    self.install_data[self.pkg] = ""
                if self.pkg:
                    self.install_data[self.pkg] += data
            elif self.mode == "install_status":
                if data == "END install status\n":
                    self.mode = None
                    return
                status = re.match(self.status_pattern, data)
                pkg_name = status.group(1)
                self.install_status[pkg_name] = status.group(2) == "OK"
            elif self.mode == "test":
                test_match = re.match(self.test_pattern, data)
                if test_match:
                    begin_end = test_match.group(1)
                    pkg_name = test_match.group(2)
                    if begin_end == "END":
                        _get_test_outputs('fastr', pkg_name, self.test_info)

    env = os.environ.copy()
    env["TMPDIR"] = install_tmp
    env['R_LIBS_USER'] = libinstall
    install_args += ['--verbose']


    # TODO enable but via installing Suggests
    #_install_vignette_support('FastR', env)

    out = OutputCapture()
    # install and (optionally) test the packages
    _log_step('BEGIN', 'install/test', 'FastR')
    rc = mx_fastr._installpkgs(stacktrace_args + install_args, nonZeroIsFatal=False, env=env, out=out, err=out)
    _log_step('END', 'install/test', 'FastR')
    if not args.install_only:
        # in order to compare the test output with GnuR we have to install/test the same
        # set of packages with GnuR, which must be present as a sibling suite
        ok_pkgs = [k for k, v in out.install_status.iteritems() if v]
        _gnur_install_test(ok_pkgs)
        _set_test_status(out.test_info)
        print 'Test Status'
        for pkg, test_status in out.test_info.iteritems():
            print '{0}: {1}'.format(pkg, test_status.status)

    shutil.rmtree(install_tmp, ignore_errors=True)
    return rc

class TestFileStatus:
    '''
    Records the status of a test file. status is either "OK" or "FAILED".
    The latter means that the file had a .fail extension.
    '''
    def __init__(self, status, abspath):
        self.status = status
        self.abspath = abspath

class TestStatus:
    '''Records the test status of a package. status ends up as either "OK" or "FAILED",
    unless GnuR also failed in which case it stays as UNKNOWN.
    The testfile_outputs dict is keyed by the relative path of the output file to
    the 'test/pkgname' directory. The value is an instance of TestFileStatus.
    '''
    def __init__(self):
        self.status = "UNKNOWN"
        self.testfile_outputs = dict()

def _pkg_testdir(suite, pkg_name):
    return join(mx.suite(suite).dir, 'test', pkg_name)

def _get_test_outputs(suite, pkg_name, test_info):
    pkg_testdir = _pkg_testdir(suite, pkg_name)
    for root, _, files in os.walk(pkg_testdir):
        if not test_info.has_key(pkg_name):
            test_info[pkg_name] = TestStatus()
        for f in files:
            ext = os.path.splitext(f)[1]
            if ext == '.R' or ext == '.prev':
                continue
            # suppress .pdf's for now (we can't compare them)
            if ext == '.pdf':
                continue
            status = "OK"
            if ext == '.fail':
                # some fatal error during the test
                status = "FAILED"
                f = os.path.splitext(f)[0]

            absfile = join(root, f)
            relfile = relpath(absfile, pkg_testdir)
            test_info[pkg_name].testfile_outputs[relfile] = TestFileStatus(status, absfile)

def _install_vignette_support(rvariant, env):
    # knitr is needed for vignettes, but FastR  can't handle it yet
    if rvariant == 'FastR':
        return
    _log_step('BEGIN', 'install vignette support', rvariant)
    args = ['--ignore-blacklist', '^rmarkdown$|^knitr$']
    _gnur_installpkgs(args, env)
    _log_step('END', 'install vignette support', rvariant)

def _gnur_installpkgs(args, env, **kwargs):
    return mx.run(['Rscript', mx_fastr._installpkgs_script()] + args, env=env, **kwargs)

def _gnur_install_test(pkgs):
    gnur_packages = join(_mx_gnur().dir, 'gnur.packages')
    with open(gnur_packages, 'w') as f:
        for pkg in pkgs:
            f.write(pkg)
            f.write('\n')
    # clone the cran test project into gnur
    gnur_cran_test_project_dir = join(_mx_gnur().dir, mx_fastr._cran_test_project())
    if not exists(gnur_cran_test_project_dir):
        shutil.copytree(mx_fastr._cran_test_project_dir(), gnur_cran_test_project_dir)
    gnur_libinstall, gnur_install_tmp = _create_libinstall(_mx_gnur())
    env = os.environ.copy()
    gnur = _mx_gnur().extensions
    path = env['PATH']
    env['PATH'] = dirname(gnur._gnur_rscript_path()) + os.pathsep + path
    env["TMPDIR"] = gnur_install_tmp
    env['R_LIBS_USER'] = gnur_libinstall

    # TODO enable but via installing Suggests
    # _install_vignette_support('GnuR', env)

    args = ['--pkg-filelist', gnur_packages]
    args += ['--run-tests']
    args += ['--ignore-blacklist']
    _log_step('BEGIN', 'install/test', 'GnuR')
    _gnur_installpkgs(args, env=env, cwd=_mx_gnur().dir, nonZeroIsFatal=False)
    _log_step('END', 'install/test', 'GnuR')

def _set_test_status(fastr_test_info):
    def _failed_outputs(outputs):
        '''
        return True iff outputs has any .fail files
        '''
        for _, testfile_status in outputs.iteritems():
            if testfile_status.status == "FAILED":
                return True
        return False

    gnur_test_info = dict()
    for pkg, _ in fastr_test_info.iteritems():
        _get_test_outputs('gnur', pkg, gnur_test_info)

    # gnur is definitive so drive off that
    for pkg in gnur_test_info.keys():
        gnur_test_status = gnur_test_info[pkg]
        fastr_test_status = fastr_test_info[pkg]
        gnur_outputs = gnur_test_status.testfile_outputs
        fastr_outputs = fastr_test_status.testfile_outputs
        if _failed_outputs(gnur_outputs):
            print "{0}: GnuR test had .fail outputs".format(pkg)
            continue

        if _failed_outputs(fastr_outputs):
            print "{0}: FastR test had .fail outputs".format(pkg)
            fastr_test_status.status = "FAILED"
            continue


        fastr_test_status.status = "OK" # optimistic
        for gnur_test_output_relpath, gnur_testfile_status in gnur_outputs.iteritems():
            if not gnur_test_output_relpath in fastr_outputs:
                fastr_test_status.status = "FAILED"
                print "{0}: FastR is missing output file: {1}".format(pkg, gnur_test_output_relpath)
                break

            gnur_content = None
            with open(gnur_testfile_status.abspath) as f:
                gnur_content = f.readlines()
            fastr_content = None
            fastr_testfile_status = fastr_outputs[gnur_test_output_relpath]
            with open(fastr_testfile_status.abspath) as f:
                fastr_content = f.readlines()

            result = _fuzzy_compare(gnur_content, fastr_content)
            if result != 0:
                fastr_test_status.status = "FAILED"
                print "{0}: FastR output mismatch: {1}".format(pkg, gnur_test_output_relpath)
                break

def _find_start(content):
    marker = "Type 'q()' to quit R."
    for i in range(len(content)):
        line = content[i]
        if marker in line:
            return i + 1

def _find_end(content):
    marker = "Time elapsed:"
    for i in range(len(content)):
        line = content[i]
        if marker in line:
            return i - 1

def _fuzzy_compare(gnur_content, fastr_content):
    gnur_start = _find_start(gnur_content) + 1 # Gnu has extra empty line
    gnur_end = _find_end(gnur_content)
    fastr_start = _find_start(fastr_content)
    fastr_len = len(fastr_content)
    result = 0
    i = gnur_start
    while i + gnur_start < gnur_end:
        gnur_line = gnur_content[i + gnur_start]
        if i + fastr_start >= fastr_len:
            result = 1
            break

        fastr_line = fastr_content[i + fastr_start]
        if gnur_line != fastr_line:
            result = 1
            break
        i = i + 1
    return result


