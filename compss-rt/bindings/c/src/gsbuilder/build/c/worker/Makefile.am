# Copyright 2013 Barcelona Supercomputing Center (www.bsc.es)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

bin_PROGRAMS = PACKAGE-worker

PACKAGE_worker_CPPFLAGS = -I../../src -I../../include -Wno-write-strings -I$(GS_HOME)/../bindings-common/include -I$../../../c/include -w

PACKAGE_worker_SOURCES = \
	PACKAGE-worker.cc \
	PACKAGE-functions.cc

PACKAGE_worker_LDFLAGS =

PACKAGE_worker_LDADD = -L../../src -ldl -lboost_iostreams -lboost_serialization

GSSTUBGEN_FLAGS = -n


PACKAGE-worker.cc PACKAGE.h: PACKAGE.idl
	$(GSSTUBGEN) $(GSSTUBGEN_FLAGS) PACKAGE.idl


