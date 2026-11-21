import 'package:flutter_test/flutter_test.dart';

import 'package:mmc_patcher/main.dart';

void main() {
  testWidgets('app renders patcher screen', (WidgetTester tester) async {
    await tester.pumpWidget(const PatcherApp());
    expect(find.text('MMC External Patcher'), findsOneWidget);
  });
}
