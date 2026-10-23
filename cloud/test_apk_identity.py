import copy,pathlib,unittest,xml.etree.ElementTree as ET
import check_apk_identity as checker

IDENTITY={'packageName':'example.product','developmentPackageName':'example.product.dev','launcherActivity':'example.source.MainActivity','fileProviderSuffixes':['.updates','.imports']}

def manifest(package,exported='0x0',grants='0xffffffff'):
    return '\n'.join('''  E: provider (line=10)
    A: android:name(0x01010003)="androidx.core.content.FileProvider"
    A: android:authorities(0x01010018)="%s%s"
    A: android:exported(0x01010010)=(type 0x12)%s
    A: android:grantUriPermissions(0x0101001b)=(type 0x12)%s'''%(package,s,exported,grants) for s in IDENTITY['fileProviderSuffixes'])

def badging(package,debug=True):
    return "package: name='%s'\nlaunchable-activity: name='example.source.MainActivity'\n%s"%(package,'application-debuggable' if debug else '')

class CompiledVariantPolicy(unittest.TestCase):
    def check_dev(self,tree=None,badge=None,identity=None):
        return checker.inspect_text(badge or badging('example.product.dev'),tree or manifest('example.product.dev'),identity or IDENTITY,'development')
    def test_independent_development_identity(self):
        self.assertEqual('example.product.dev',self.check_dev()['packageName'])
    def test_original_release_identity_stays_valid(self):
        self.assertFalse(checker.inspect_text(badging('example.product',False),manifest('example.product'),IDENTITY,'release')['debuggable'])
    def test_production_package_rejected_as_development(self):
        with self.assertRaises(ValueError):self.check_dev(badge=badging('example.product'))
    def test_hardcoded_production_providers_rejected(self):
        with self.assertRaises(ValueError):self.check_dev(tree=manifest('example.product'))
    def test_exported_file_provider_rejected(self):
        with self.assertRaises(ValueError):self.check_dev(tree=manifest('example.product.dev',exported='0xffffffff'))
    def test_uri_grants_required(self):
        with self.assertRaises(ValueError):self.check_dev(tree=manifest('example.product.dev',grants='0x0'))
    def test_missing_provider_rejected(self):
        with self.assertRaises(ValueError):self.check_dev(tree='E: application (line=1)')
    def test_wrong_launcher_namespace_rejected(self):
        with self.assertRaises(ValueError):self.check_dev(badge=badging('example.product.dev').replace('example.source.MainActivity','example.product.dev.MainActivity'))
    def test_debuggable_release_rejected(self):
        with self.assertRaises(ValueError):checker.inspect_text(badging('example.product'),manifest('example.product'),IDENTITY,'release')

class SourceProviderSeparation(unittest.TestCase):
    def test_both_variant_provider_graphs_are_private_and_disjoint(self):
        import json
        root=pathlib.Path(__file__).resolve().parent.parent
        identity=json.loads((root/'release/signing-identity.json').read_text())
        tree=ET.parse(root/'app/src/main/AndroidManifest.xml');android='{http://schemas.android.com/apk/res/android}'
        authorities=[n.get(android+'authorities') for n in tree.findall('./application/provider') if n.get(android+'name')=='androidx.core.content.FileProvider']
        graphs=[]
        for package in [identity['packageName'],identity['developmentPackageName']]:
            graph={value.replace('${applicationId}',package) for value in authorities}
            self.assertTrue({package+suffix for suffix in identity['fileProviderSuffixes']}.issubset(graph))
            graphs.append(graph)
        self.assertTrue(graphs[0].isdisjoint(graphs[1]),'Development and production FileProviders collide.')
        for provider in tree.findall('./application/provider'):
            if provider.get(android+'name')=='androidx.core.content.FileProvider':
                self.assertEqual('false',provider.get(android+'exported'))
                self.assertEqual('true',provider.get(android+'grantUriPermissions'))

if __name__=='__main__':unittest.main()
