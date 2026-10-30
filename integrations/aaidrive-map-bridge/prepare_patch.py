from pathlib import Path
import difflib
import sys

repo=Path(sys.argv[1])
overlay=Path(__file__).resolve().parent
changes={}
manifest=Path('app/src/gmap/AndroidManifest.xml')
s=(repo/manifest).read_text()
entry='''        <!-- Map-only IPC, restricted to the LibreCR UID inside the service. -->
        <service android:name=".carapp.maps.LibreCRMapService" android:exported="true" />
'''
assert 'LibreCRMapService' not in s, 'Map bridge is already present'
changes[manifest]=s.replace('    </application>',entry+'    </application>')
controller=Path('app/src/gmap/java/me/hufman/androidautoidrive/carapp/maps/GMapsController.kt')
s=(repo/controller).read_text()
needle='\tprivate fun drawNavigation() {\n'
assert needle in s
changes[controller]=s.replace(needle,needle+'\t\tLibreCRMapRoute.publish(navController.currentNavDestination, navController.currentNavRoute)\n')
service=Path('app/src/gmap/java/me/hufman/androidautoidrive/carapp/maps/MapAppService.kt')
s=(repo/service).read_text()
needle='\toverride fun onCarStop() {\n'
assert needle in s
changes[service]=s.replace(needle,needle+'\t\tLibreCRMapRoute.publish(null, null)\n')
projection=Path('app/src/gmap/java/me/hufman/androidautoidrive/carapp/maps/GMapsProjection.kt')
s=(repo/projection).read_text()
needle='val locationSource: GMapsLocationSource): Presentation'
assert needle in s
s=s.replace(needle,'val locationSource: GMapsLocationSource, val insetForSidebar: Boolean = true): Presentation')
needle='val margin = (fullDimensions.appWidth - sidebarDimensions.appWidth) / 2'
assert needle in s
s=s.replace(needle,'val margin = if (insetForSidebar) (fullDimensions.appWidth - sidebarDimensions.appWidth) / 2 else 16')
needle='map?.setPadding(margin, 0, margin, 0)'
assert needle in s
changes[projection]=s.replace(needle,'map?.setPadding(margin, 0, margin, if (insetForSidebar) 0 else 16)')
for source in sorted((overlay/'src').rglob('*.kt')):
    changes[Path('app')/source.relative_to(overlay)]=source.read_text()
patch=[]
for path,new in changes.items():
    old=(repo/path).read_text() if (repo/path).exists() else ''
    for line in difflib.unified_diff(old.splitlines(keepends=True),new.splitlines(keepends=True),
        fromfile='a/'+path.as_posix() if old else '/dev/null',tofile='b/'+path.as_posix()):
        patch.append(line if line.endswith('\n') else line+'\n\\ No newline at end of file\n')
(overlay/'aaidrive-map-bridge.patch').write_text(''.join(patch))
print(f'Prepared patch for {len(changes)} AAIdrive files.')
