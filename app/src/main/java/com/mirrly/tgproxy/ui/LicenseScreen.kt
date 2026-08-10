/*
 * Mirrly TG Proxy - Native MTProto & Cloudflare WebSocket Proxy for Android
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mirrly.tgproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrly.tgproxy.R
import com.mirrly.tgproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var selectedLanguage by remember { mutableStateOf("ru") }

    val fullLicenseTextEn = remember {
        """
                    GNU GENERAL PUBLIC LICENSE
                       Version 3, 29 June 2007

 Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

                            Preamble

  The GNU General Public License is a free, copyleft license for
software and other kinds of works.

  The licenses for most software and other practical works are designed
to take away your freedom to share and change the works.  By contrast,
the GNU General Public License is intended to guarantee your freedom to
share and change all versions of a program--to make sure it remains free
software for all its users.  We, the Free Software Foundation, use the
GNU General Public License for most of our software; it applies also to
any other work released this way by its authors.  You can apply it to
your programs, too.

  When we speak of free software, we are referring to freedom, not
price.  Our General Public Licenses are designed to make sure that you
have the freedom to distribute copies of free software (and charge for
them if you wish), that you receive source code or can get it if you
want it, that you can change the software or use pieces of it in new
free programs, and that you know you can do these things.

  To protect your rights, we need to prevent others from denying you
these rights or asking you to surrender the rights.  Therefore, you have
certain responsibilities if you distribute copies of the software, or if
you modify it: responsibilities to respect the freedom of others.

  For example, if you distribute copies of such a program, whether
gratis or for a fee, you must give the recipients the same freedoms that
you received.  You must make sure that they, too, receive or can get the
source code.  And you must show them these terms so they know their
rights.

  Developers that use the GNU GPL protect your rights with two steps:
(1) assert copyright on the software, and (2) offer you this License
giving you legal permission to copy, distribute and/or modify it.

  For the developers' and authors' protection, the GPL clearly explains
that there is no warranty for this free software.  For both users' and
authors' sake, the GPL requires that modified versions be marked as
changed, so that their problems will not be attributed erroneously to
authors of previous versions.

  Some devices are designed to deny users access to install or run
modified versions of the software inside them, although the manufacturer
can do so.  This is incompatible with the aim of protecting users'
freedom to change the software.  The systematic pattern of such abuses
occurs in the area of products for individuals to use, which is precisely
where it is most unacceptable.  Therefore, we have designed this version
of the GPL to prohibit the practice for those products.  If such problems
arise substantially in other domains, we stand ready to extend this
provision to those domains in future versions of the GPL, as needed to
protect the freedom of users.

  Finally, every program is threatened constantly by software patents.
States should not allow patents to restrict development and use of
software on general-purpose computers, but in those that do, we wish to
avoid the special danger that patents applied to a free program could
make it effectively proprietary.  To prevent this, the GPL assures that
patents cannot be used to render the program non-free.

  The precise terms and conditions for copying, distribution and
modification follow.

                       TERMS AND CONDITIONS

  0. Definitions.

  "This License" refers to version 3 of the GNU General Public License.

  "Copyright" also means copyright-like laws that apply to other kinds of
works, such as semiconductor masks.

  "The Program" refers to any copyrightable work licensed under this
License.  Each licensee is addressed as "you".  "Licensees" and
"recipients" may be individuals or organizations.

  To "modify" a work means to copy from or adapt all or part of the work
in a fashion requiring copyright permission, other than the making of an
exact copy.  The resulting work is called a "modified version" of the
earlier work or a work "based on" the earlier work.

  A "covered work" means either the unmodified Program or a work based
on the Program.

  To "propagate" a work means to do anything with it that, without
permission, would make you directly or secondarily liable for
infringement under applicable copyright law, except executing it on a
computer or modifying a private copy.  Propagation includes copying,
distribution (with or without modification), making available to the
public, and in some countries other activities as well.

  To "convey" a work means any kind of propagation that enables other
parties to make or receive copies.  Mere interaction with a user through
a computer network, with no transfer of a copy, is not conveying.

  An interactive user interface displays "Appropriate Legal Notices"
to the extent that it includes a convenient and prominently visible
feature that (1) displays an appropriate copyright notice, and (2)
tells the user that there is no warranty for the work (except to the
extent that warranties are provided), that licensees may convey the
work under this License, and how to view a copy of this License.  If
the interface presents a list of user commands or options, such as a
menu, a prominent item in the list meets this criterion.

  1. Source Code.

  The "source code" for a work means the preferred form of the work
for making modifications to it.  "Object code" means any non-source
form of a work.

  A "Standard Interface" means an interface that either is an official
standard defined by a recognized standards body, or, in the case of
interfaces specified for a particular programming language, one that
is widely used among developers working in that language.

  The "System Libraries" of an executable work include anything, other
than the work as a whole, that (a) is included in the normal form of
packaging a Major Component, but which is not part of that Major
Component, and (b) serves only to enable use of the work with that
Major Component, or to implement a Standard Interface for which an
implementation is available to the public in source code form.  A
"Major Component", in this context, means a major essential component
(kernel, window system, and so on) of the specific operating system
(if any) on which the executable work runs, or a compiler used to
produce the work, or an object code interpreter used to run it.

  The "Corresponding Source" for a work in object code form means all
the source code needed to generate, install, and (for an executable
work) run the object code and to modify the work, including scripts to
control those activities.  However, it does not include the work's
System Libraries, or general-purpose tools or generally available free
programs which are used unmodified in performing those activities but
which are not part of the work.  For example, Corresponding Source
includes interface definition files associated with source files for
the work, and the source code for shared libraries and dynamically
linked subprograms that the work is specifically designed to require,
such as by intimate data communication or control flow between those
subprograms and other parts of the work.

  The Corresponding Source need not include anything that users
can regenerate automatically from other parts of the Corresponding
Source.

  The Corresponding Source for a work in source code form is that
same work.

  2. Basic Permissions.

  All rights granted under this License are granted for the term of
copyright on the Program, and are irrevocable provided the stated
conditions are met.  This License explicitly affirms your unlimited
permission to run the unmodified Program.  The output from running a
covered work is covered by this License only if the output, given its
content, constitutes a covered work.  This License acknowledges your
rights of fair use or other equivalent, as provided by copyright law.

  You may make, run and propagate covered works that you do not
convey, without conditions so long as your license otherwise remains
in force.  You may convey covered works to others for the sole purpose
of having them make modifications exclusively for you, or provide you
with facilities for running those works, provided that you comply with
the terms of this License in conveying all material for which you do
not control copyright.  Those thus making or running the covered works
for you must do so exclusively on your behalf, under your direction
and control, on terms that prohibit them from making any copies of
your copyrighted material outside their relationship with you.

  Conveying under any other circumstances is permitted solely under
the conditions stated below.  Sublicensing is not allowed; section 10
makes it unnecessary.

  3. Protecting Users' Legal Rights From Anti-Circumvention Law.

  No covered work shall be deemed part of an effective technological
measure under any applicable law fulfilling obligations under article
11 of the WIPO copyright treaty adopted on 20 December 1996, or
similar laws prohibiting or restricting circumvention of such
measures.

  When you convey a covered work, you waive any legal power to forbid
circumvention of technological measures to the extent such circumvention
is effected by exercising rights under this License with respect to
the covered work, and you disclaim any intention to limit operation or
modification of the work as a means of enforcing, against the work's
users, your or third parties' legal rights to forbid circumvention of
technological measures.

  4. Conveying Verbatim Copies.

  You may convey verbatim copies of the Program's source code as you
receive it, in any medium, provided that you conspicuously and
appropriately publish on each copy an appropriate copyright notice;
keep intact all notices stating that this License and any
non-permissive terms added in accord with section 7 apply to the code;
keep intact all notices of the absence of any warranty; and give all
recipients a copy of this License along with the Program.

  You may charge any price or no price for each copy that you convey,
and you may offer support or warranty protection for a fee.

  5. Conveying Modified Source Versions.

  You may convey a work based on the Program, or the modifications to
produce it from the Program, in the form of source code under the
terms of section 4, provided that you also meet all of these conditions:

    a) The work must carry prominent notices stating that you modified
    it, and giving a relevant date.

    b) The work must carry prominent notices stating that it is
    released under this License and any conditions added under section
    7.  This requirement modifies the requirement in section 4 to
    "keep intact all notices".

    c) You must license the entire work, as a whole, under this
    License to anyone who comes into possession of a copy.  This
    License will therefore apply, along with any applicable section 7
    additional terms, to the whole of the work, and all its parts,
    regardless of how they are packaged.  This License gives no
    permission to license the work in any other way, but it does not
    invalidate such permission if you have separately received it.

    d) If the work has interactive user interfaces, each must display
    Appropriate Legal Notices; however, if the Program has interactive
    interfaces that do not display Appropriate Legal Notices, your
    work need not make them do so.

  A compilation of a covered work with other separate and independent
works, which are not by their nature extensions of the covered work,
and which are not combined with it such as to form a larger program,
in or on a volume of a storage or distribution medium, is called an
"aggregate" if the compilation and its resulting copyright are not
used to limit the access or legal rights of the compilation's users
beyond what the individual works permit.  Inclusion of a covered work
in an aggregate does not cause this License to apply to the other
parts of the aggregate.

  6. Conveying Non-Source Forms.

  You may convey a covered work in object code form under the terms
of sections 4 and 5, provided that you also convey the
Machine-Readable Corresponding Source under the terms of this License,
in one of these ways:

    a) Convey the object code in, or embodied in, a physical product
    (including a physical distribution medium), accompanied by the
    Corresponding Source fixed on a durable physical medium
    customarily used for software interchange.

    b) Convey the object code in, or embodied in, a physical product
    (including a physical distribution medium), accompanied by a
    written offer, valid for at least three years and valid for as
    long as you offer spare parts or customer support for that product
    model, to give anyone who possesses the object code either (1) a
    copy of the Corresponding Source for all the software in the
    product that is covered by this License, on a durable physical
    medium customarily used for software interchange, for a price no
    more than your reasonable cost of physically performing this
    conveying of source, or (2) access to copy the
    Corresponding Source from a network server at no charge.

    c) Convey individual copies of the object code with a copy of the
    written offer to provide the Corresponding Source.  This
    alternative is allowed only occasionally and noncommercially, and
    only if you received the object code with such an offer, in accord
    with subsection 6b.

    d) Convey the object code by offering access from a designated
    place (gratis or for a charge), and offer equivalent access to the
    Corresponding Source in the same way through the same place at no
    further charge.  You need not require recipients to copy the
    Corresponding Source along with the object code.  If the place to
    copy the object code is a network server, the Corresponding Source
    may be on a different server (operated by you or a third party)
    that supports equivalent copying facilities, provided you maintain
    clear directions next to the object code saying where to find the
    Corresponding Source.  Regardless of what server hosts the
    Corresponding Source, you remain obligated to ensure that it is
    available for as long as needed to satisfy these requirements.

    e) Convey the object code using peer-to-peer transmission, provided
    you inform other peers where the object code and Corresponding
    Source of the work are being offered to the general public at no
    charge under subsection 6d.

  A separable portion of the object code, whose source code is excluded
from the Corresponding Source as a System Library, need not be
included in conveying the object code work.

  A "User Product" is either (1) a "consumer product", which means any
tangible personal property which is normally used for personal, family,
or household purposes, or (2) anything designed or sold for incorporation
into a dwelling.  In determining whether a product is a consumer product,
doubtful cases shall be resolved in favor of coverage.  For a particular
product received by a particular user, "normally used" refers to a
typical or common use of that class of product, regardless of the status
of the particular user or of the way in which the particular user
actually uses, or expects or is expected to use, the product.  A product
is a consumer product regardless of whether the product has substantial
commercial, industrial or non-consumer uses, unless such uses represent
the only significant mode of use of the product.

  "Installation Information" for a User Product means any methods,
procedures, authorization keys, or other information required to install
and execute modified versions of a covered work in that User Product from
a modified version of its Corresponding Source.  The information must
suffice to ensure that the continued functioning of the modified object
code is in no case prevented or interfered with solely because
modification has been made.

  If you convey an object code work under this section in, or with, or
specifically for use in, a User Product, and the conveying occurs as
part of a transaction in which the right of possession and use of the
User Product is transferred to the recipient in perpetuity or for a
fixed term (regardless of how the transaction is characterized), the
Corresponding Source conveyed under this section must be accompanied
by the Installation Information.  But this requirement does not apply
if neither you nor any third party retains the ability to install
modified object code on the User Product (for example, the work has
been installed in ROM).

  The requirement to provide Installation Information does not include a
requirement to continue to provide support service, warranty, or updates
for a work that has been modified or installed by the recipient, or for
the User Product in which it has been modified or installed.  Access to a
network may be denied when the modification itself materially and
adversely affects the operation of the network or violates the rules and
protocols for communication across the network.

  Corresponding Source conveyed, and Installation Information provided,
in accord with this section must be in a format that is publicly
documented (and with an implementation available to the public in
source code form), and must require no special password or key for
unpacking, reading or copying.

  7. Additional Terms.

  "Additional permissions" are terms that supplement the terms of this
License by making exceptions from one or more of its conditions.
Additional permissions that are applicable to the entire Program shall
be treated as though they were included in this License, to the extent
that they are valid under applicable law.  If additional permissions
apply only to part of the Program, that part may be used separately
under those permissions, but the entire Program remains governed by
this License without regard to the additional permissions.

  When you convey a copy of a covered work, you may at your option
remove any additional permissions from that copy, or from any part of
it.  (Additional permissions may be written to require their own
removal in certain cases when you modify the work.)  You may place
additional permissions on material, added by you to a covered work,
for which you have or can give appropriate copyright permission.

  Notwithstanding any other provision of this License, for material you
add to a covered work, you may (if authorized by the copyright owners of
that material) supplement the terms of this License with terms:

    a) Disclaiming warranty or limiting liability differently from the
    terms of sections 15 and 16 of this License; or

    b) Requiring preservation of specified reasonable legal notices or
    author attributions in that material or in the Appropriate Legal
    Notices displayed by works containing it; or

    c) Prohibiting misrepresentation of the origin of that material, or
    requiring that modified versions of such material be marked in
    reasonable ways as different from the original version; or

    d) Limiting the use for publicity purposes of names of licensors or
    authors of the material; or

    e) Declining to grant rights under trademark law for use of some
    trade names, trademarks, or service marks; or

    f) Requiring indemnification of licensors and authors of that
    material by anyone who conveys the material (or modified versions of
    it) with contractual assumptions of liability to the recipient, for
    any liability that these contractual assumptions directly impose on
    those licensors and authors.

  All other non-permissive additional terms are considered "further
restrictions" within the meaning of section 10.  If the Program as you
received it, or any part of it, contains a notice stating that it is
governed by this License along with a term that is a further
restriction, you may remove that term.  If a license document contains
a further restriction but permits relicensing or conveying under this
License, you may add to a covered work material governed by the terms
of that license document, provided that the further restriction does
not survive such relicensing or conveyance.

  If you add terms to a covered work in accord with this section, you
must place, in the relevant source files, a statement of the
additional terms that apply to those files, or a notice indicating
where to find the applicable terms.

  Additional terms, permissive or non-permissive, may be stated in the
form of a separately written license, or stated as exceptions;
the above requirements apply either way.

  8. Termination.

  You may not propagate or convey a covered work except as expressly
provided under this License.  Any attempt otherwise to propagate or
convey it is void, and will automatically terminate your rights under
this License (including any patent licenses granted under the third
paragraph of section 11).

  However, if you cease all violation of this License, then your
license from a particular copyright holder is reinstated (a)
provisionally, unless and until the copyright holder explicitly and
finally terminates your license, and (b) permanently, if the copyright
holder fails to notify you of the violation by some reasonable means
prior to 60 days after the cessation.

  Moreover, your license from a particular copyright holder is
reinstated permanently if the copyright holder notifies you of the
violation by some reasonable means, this is the first time you have
received notice of violation of this License (for any work) from that
copyright holder, and you cure the violation prior to 30 days after
your receipt of the notice.

  Termination of your rights under this section does not terminate the
licenses of parties who have received copies or rights from you under
this License.  If your rights have been terminated and not permanently
reinstated, you do not qualify to receive new licenses for the same
material under section 10.

  9. Acceptance Not Required for Having Copies.

  You do not have to accept this License in order to receive or run a
copy of the Program.  Ancillary propagation of a covered work occurring
solely as a consequence of using peer-to-peer transmission to receive a
copy likewise does not require acceptance.  However, nothing other than
this License grants you permission to propagate or convey any covered
work.  These actions infringe copyright if you do not accept this
License.  Therefore, by modifying or propagating a covered work, you
indicate your acceptance of this License to do so.

  10. Automatic Licensing of Downstream Recipients.

  Each time you convey a covered work, the recipient automatically
receives a license from the original licensors, to run, modify and
propagate that work, subject to this License.  You are not responsible
for enforcing compliance by third parties with this License.

  An "entity transaction" is a transaction transferring control of an
organization, or substantially all assets of one, or subdividing an
organization, or merging organizations.  If propagation of a covered
work results from an entity transaction, each party to that
transaction who receives a copy of the work also receives whatever
licenses to the work the party's predecessor in interest had or could
give under the previous paragraph, plus a right to possession of the
Corresponding Source of the work from the predecessor in interest, if
the predecessor has it or can get it with reasonable efforts.

  You may not impose any further restrictions on the exercise of the
rights granted or affirmed under this License.  For example, you may
not impose a license fee, royalty, or other charge for exercise of
rights granted under this License, and you may not initiate litigation
(including a cross-claim or counterclaim in a lawsuit) alleging that
any patent claim is infringed by making, using, selling, offering for
sale, or importing the Program or any portion of it.

  11. Patents.

  A "contributor" is a copyright holder who authorizes use under this
License of the Program or a work on which the Program is based.  The
work thus licensed is called the contributor's "contributor version".

  A contributor's "essential patent claims" are all patent claims
owned or controlled by the contributor, whether already acquired or
hereafter acquired, that would be infringed by some manner, permitted
by this License, of making, using, or selling its contributor version,
but do not include claims that would be infringed only as a
consequence of further modification of the contributor version.  For
purposes of this definition, "control" includes the right to grant
patent sublicenses in a manner consistent with the requirements of
this License.

  Each contributor grants you a non-exclusive, worldwide, royalty-free
patent license under the contributor's essential patent claims, to
make, use, sell, offer for sale, import and otherwise run, modify and
propagate the contents of its contributor version.

  In the following three paragraphs, a "patent license" is any express
agreement or commitment, however denominated, not to enforce a patent
(such as an express permission to practice a patent or covenant not to
sue for patent infringement).  To "grant" such a patent license to a
party means to make such an agreement or commitment not to enforce a
patent against the party.

  If you convey a covered work, knowingly relying on a patent license,
and the Corresponding Source of the work is not available for anyone
to copy, free of charge and under the terms of this License, through a
publicly available network server or other readily accessible means,
then you must either (1) cause the Corresponding Source to be so
available, or (2) arrange to deprive yourself of the benefit of the
patent license for this particular work, or (3) arrange, in a manner
consistent with the requirements of this License, to extend the patent
license to downstream recipients.  "Knowingly relying" means you have
actual knowledge that, but for the patent license, your conveying the
covered work in a country, or your recipient's use of the covered work
in a country, would infringe one or more identifiable patents in that
country that you have reason to believe are valid.

  If, pursuant to or in connection with a single transaction or
arrangement, you convey, or propagate by procuring conveyance of, a
covered work, and grant a patent license to some of the parties
receiving the covered work authorizing them to use, propagate, modify
or convey a specific copy of the covered work, then the patent license
you grant is automatically extended to all recipients of the covered
work and works based on it.

  A patent license is "discriminatory" if it does not include within
the scope of its coverage, prohibits the exercise of, or is
conditioned on the non-exercise of one or more of the rights that are
specifically granted under this License.  You may not convey a covered
work if you are a party to an agreement with a third party that is in
the business of distributing software, under which you make payment to
the third party based on the extent of your activity of conveying the
work, and under which the third party grants, to any of the parties
who would receive the covered work from you, a discriminatory patent
license (a) in connection with copies of the covered work conveyed by
you (or copies made from those copies), or (b) primarily for and in
connection with specific products or compilations that contain the
covered work, unless you entered into that agreement, or that patent
license was granted, prior to 28 March 2007.

  Nothing in this License shall be construed as excluding or limiting
any implied license or other defenses to infringement that may
otherwise be available to you under applicable patent law.

  12. No Surrender of Others' Freedom.

  If conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot convey a
covered work so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not convey it at all.  For example, if you agree to terms that obligate
you to collect a royalty for conveying from those to whom you convey
the Program, the only way you could satisfy both those terms and this
License would be to refrain entirely from conveying the Program.

  13. Use with the GNU Affero General Public License.

  Notwithstanding any other provision of this License, you have
permission to link or combine any covered work with a work licensed
under version 3 of the GNU Affero General Public License into a single
combined work, and to convey the resulting work.  The terms of this
License will continue to apply to the part which is the covered work,
but the special requirements of the GNU Affero General Public License,
section 13, concerning interaction through a network will apply to the
combination as such.

  14. Revised Versions of this License.

  The Free Software Foundation may publish revised and/or new versions of
the GNU General Public License from time to time.  Such new versions will
be similar in spirit to the present version, but may differ in detail to
address new problems or concerns.

  Each version is given a distinguishing version number.  If the
Program specifies that a certain numbered version of the GNU General
Public License "or any later version" applies to it, you have the
option of following the terms and conditions either of that numbered
version or of any later version published by the Free Software
Foundation.  If the Program does not specify a version number of the
GNU General Public License, you may choose any version ever published
by the Free Software Foundation.

  If the Program specifies that a proxy can decide which future
versions of the GNU General Public License can be used, that proxy's
public statement of acceptance of a version permanently authorizes you
to choose that version for the Program.

  Later license versions may give you additional or different
permissions.  However, no additional obligations are imposed on any
author or developer as a result of your choosing to follow a
later version.

  15. Disclaimer of Warranty.

  THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY
APPLICABLE LAW.  EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT
HOLDERS AND/OR OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY
OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM
IS WITH YOU.  SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF
ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. Limitation of Liability.

  IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING
WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MODIFIES AND/OR CONVEYS
THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY
GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE
USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED TO LOSS OF
DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD
PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS),
EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF
SUCH DAMAGES.

  17. Interpretation of Sections 15 and 16.

  If the disclaimer of warranty and limitation of liability provided
above cannot be given local legal effect according to their terms,
reviewing courts shall apply local law that most closely approximates
an absolute waiver of all civil liability in connection with the
Program, unless a warranty or assumption of liability accompanies a
copy of the Program in return for a fee.

                     END OF TERMS AND CONDITIONS

  Copyright (C) 2026 R1Xern (Mirrly Dev)

  Mirrly TG Proxy is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <https://www.gnu.org/licenses/>.
        """.trimIndent()
    }

    val fullLicenseTextRu = remember {
        """
                    СВОБОДНАЯ ОБЩЕСТВЕННАЯ ЛИЦЕНЗИЯ GNU
                        Версия 3, 29 июня 2007 г.

 Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
 Каждому разрешается копировать и распространять точные копии
 этого лицензионного документа, но его изменение не допускается.

                             Преамбула

   Стандартная общественная лицензия GNU (GNU General Public License) представляет собой свободную копилефт-лицензию для программного обеспечения и других видов произведений.

   Лицензии для большинства программных продуктов и других практических произведений предназначены для того, чтобы лишить вас свободы делиться ими и изменять их. Напротив, Стандартная общественная лицензия GNU предназначена для гарантирования вашей свободы делиться всеми версиями программы и изменять их — чтобы убедиться, что она остается свободным ПО для всех своих пользователей. Мы, Фонд свободного программного обеспечения (Free Software Foundation), используем Стандартную общественную лицензию GNU для большинства нашего программного обеспечения; она также применяется к любому другому произведению, выпущенному таким образом его авторами. Вы также можете применить её к своим программам.

   Когда мы говорим о свободном программном обеспечении, мы имеем в виду свободу, а не цену. Наши Стандартные общественные лицензии разработаны для того, чтобы гарантировать, что вы имеете свободу распространять копии свободного ПО (и взимать за них плату, если пожелаете), что вы получаете исходный код или можете получить его, если захотите, что вы можете изменять ПО или использовать его части в новых свободных программах, и что вы знаете о возможности совершать эти действия.

   Чтобы защитить ваши права, нам необходимо предотвратить ситуации, когда другие лица отказывают вам в этих правах или требуют от вас отказаться от них. Таким образом, на вас возлагаются определенные обязанности при распространении копий ПО или при его модификации: обязанности уважать свободу других.

   Например, если вы распространяете копии такой программы, безвозмездно или за плату, вы должны предоставить получателям те же свободы, которые получили вы. Вы должны убедиться, что они также получают или могут получить исходный код. И вы должны показать им эти условия, чтобы они знали свои права.

   Разработчики, использующие GNU GPL, защищают ваши права в два этапа: (1) заявляют авторские права на программное обеспечение, и (2) предлагают вам эту Лицензию, дающую законное разрешение на копирование, распространение и/или изменение ПО.

   В целях защиты разработчиков и авторов GPL четко разъясняет, что на это свободное программное обеспечение не предоставляется никаких гарантий. В интересах как пользователей, так и авторов GPL требует, чтобы модифицированные версии помечались как измененные, дабы их проблемы не приписывались ошибочно авторам предыдущих версий.

   Некоторые устройства предназначены для того, чтобы отказывать пользователям в доступе к установке или запуску модифицированных версий программного обеспечения внутри них, хотя производитель может это делать. Это несовместимо с целью защиты свободы пользователей по изменению ПО. Систематический характер таких злоупотреблений наблюдается в сфере продуктов личного пользования, где это наиболее недопустимо. Поэтому мы разработали эту версию GPL, чтобы запретить подобную практику для таких продуктов. Если аналогичные проблемы существенно проявятся в других областях, мы готовы распространить это положение на данные области в будущих версиях GPL по мере необходимости для защиты свободы пользователей.

   Наконец, каждая программа находится под постоянной угрозой со стороны программных патентов. Государства не должны допускать использования патентов для ограничения разработки и использования ПО на компьютерах общего назначения, но в тех странах, где это происходит, мы стремимся избежать особой опасности, заключающейся в том, что патенты, примененные к свободной программе, могут сделать её фактически проприетарной. Чтобы предотвратить это, GPL гарантирует, что патенты не могут быть использованы для того, чтобы сделать программу несвободной.

   Точные условия и положения для копирования, распространения и модификации приведены ниже.

                        УСЛОВИЯ И ПОЛОЖЕНИЯ

   0. Определения.

   «Настоящая Лицензия» относится к версии 3 Стандартной общественной лицензии GNU (GNU General Public License).

   «Авторское право» также означает законы, аналогичные авторскому праву, применяемые к другим видам произведений, таким как топологии интегральных микросхем.

   «Программа» относится к любому произведению, защищенному авторским правом и лицензированному в соответствии с настоящей Лицензией. Каждый лицензиат именуется «вы». «Лицензиаты» и «получатели» могут быть физическими или юридическими лицами.

   «Модифицировать» произведение означает копировать или адаптировать все произведение или его часть способом, требующим разрешения авторского права, за исключением создания точной копии. Полученное произведение называется «модифицированной версией» предыдущего произведения или произведением, «основанным на» предыдущем произведении.

   «Покрываемое произведение» означает либо немодифицированную Программу, либо произведение, основанное на Программе.

   «Распространять» (propagate) произведение означает совершать с ним любые действия, которые без разрешения сделали бы вас прямо или косвенно ответственным за нарушение применимого законодательства об авторском праве, за исключением его выполнения на компьютере или модификации частной копии. Распространение включает копирование, предоставление (с модификацией или без), доведение до всеобщего сведения, а в некоторых странах и другие виды деятельности.

   «Передавать» (convey) произведение означает любой вид распространения, позволяющий другим сторонам создавать или получать копии. Простая интерактивная работа с пользователем через компьютерную сеть без передачи копии передачей не является.

   Интерактивный пользовательский интерфейс отображает «Соответствующие юридические уведомления» в той мере, в какой он включает удобный и хорошо заметный элемент, который (1) отображает соответствующее уведомление об авторских правах и (2) сообщает пользователю об отсутствии гарантий на произведение (за исключением случаев, когда гарантии предоставляются), о том, что лицензиаты могут передавать произведение на условиях настоящей Лицензии, и о том, как просмотреть копию настоящей Лицензии. Если интерфейс представляет список пользовательских команд или опций, таких как меню, видный пункт в этом списке соответствует данному критерию.

   1. Исходный код.

   «Исходный код» произведения означает предпочтительную форму произведения для внесения в него изменений. «Объектный код» означает любую форму произведения, не являющуюся исходным кодом.

   «Стандартный интерфейс» означает интерфейс, который либо является официальным стандартом, определенным признанным органом по стандартизации, либо, в случае интерфейсов, специфицированных для конкретного языка программирования, широко используется разработчиками, работающими на этом языке.

   «Системные библиотеки» исполняемого произведения включают в себя все, кроме произведения в целом, что (a) включено в обычную форму упаковки Основного компонента, но не является частью этого Основного компонента, и (b) служит только для включения возможности использования произведения с этим Основным компонентом или для реализации Стандартного интерфейса, реализация которого доступна общественности в форме исходного кода. «Основной компонент» в данном контексте означает основной необходимый компонент (ядро, оконная система и т. д.) конкретной операционной системы (если таковая имеется), в которой выполняется исполняемое произведение, или компилятор, используемый для создания произведения, или интерпретатор объектного кода, используемый для его запуска.

   «Соответствующий исходный код» для произведения в форме объектного кода означает весь исходный код, необходимый для создания, установки и (для исполняемого произведения) запуска объектного кода, а также для изменения произведения, включая скрипты для управления этими действиями. Однако он не включает Системные библиотеки произведения, либо инструменты общего назначения или общедоступные свободные программы, которые используются в неизмененном виде при выполнении этих действий, но не являются частью произведения. Например, Соответствующий исходный код включает файлы определения интерфейсов, связанные с исходными файлами произведения, и исходный код для общих библиотек и динамически связываемых подпрограмм, для работы которых произведение специально предназначено (например, путем тесного обмена данными или управления между этими подпрограммами и другими частями произведения).

   Соответствующий исходный код не должен включать ничего из того, что пользователи могут автоматически сгенерировать из других частей Соответствующего исходного кода.

   Соответствующий исходный код для произведения в форме исходного кода представляет собой то же самое произведение.

   2. Основные разрешения.

   Все права, предоставленные настоящей Лицензией, предоставляются на срок действия авторского права на Программу и являются безотзывными при соблюдении указанных условий. Настоящая Лицензия явно подтверждает ваше неограниченное разрешение на запуск немодифицированной Программы. Результат выполнения покрываемого произведения подпадает под действие настоящей Лицензии только в том случае, если этот результат по своему содержанию представляет собой покрываемое произведение. Настоящая Лицензия признает ваши права на добросовестное использование (fair use) или другие эквивалентные права, предусмотренные законодательством об авторском праве.

   Вы можете создавать, запускать и распространять покрываемые произведения, которые вы не передаете, без ограничений до тех пор, пока ваша лицензия остается в силе. Вы можете передавать покрываемые произведения другим лицам исключительно с целью внесения ими изменений исключительно для вас или предоставления вам средств для запуска этих произведений при условии, что вы соблюдаете условия настоящей Лицензии при передаче всех материалов, на которые вы не контролируете авторские права. Лица, создающие или запускающие покрываемые произведения для вас, должны делать это исключительно от вашего имени, под вашим руководством и контролем, на условиях, запрещающих им создавать какие-либо копии ваших материалов, защищенных авторским правом, вне рамок их отношений с вами.

   Передача при любых других обстоятельствах разрешается исключительно на условиях, указанных ниже. Сублицензирование не допускается; Раздел 10 делает его ненужным.

   3. Защита юридических прав пользователей от законов об обходе технических средств.

   Никакое покрываемое произведение не должно считаться частью эффективной технической меры защиты в рамках любых применимых законов во исполнение обязательств по статье 11 Договора ВОИС по авторскому праву, принятого 20 декабря 1996 года, или аналогичных законов, запрещающих или ограничивающих обход таких мер.

   Когда вы передаете покрываемое произведение, вы отказываетесь от любых юридических полномочий запрещать обход технических мер в той мере, в какой такой обход осуществляется путем реализации прав по настоящей Лицензии в отношении покрываемого произведения, и вы отказываетесь от любого намерения ограничить работу или модификацию произведения в качестве средства обеспечения защиты ваших или третьих лиц юридических прав на запрет обхода технических мер защиты.

   4. Передача точных копий.

   Вы можете передавать точные копии исходного кода Программы в том виде, в каком вы его получили, на любом носителе, при условии, что вы заметным образом и надлежащим образом опубликуете на каждой копии соответствующее уведомление об авторских правах; сохраните в неприкосновенности все уведомления о том, что к коду применяется настоящая Лицензия и любые неразрешительные условия, добавленные в соответствии с Разделом 7; сохраните в неприкосновенности все уведомления об отсутствии каких-либо гарантий; и предоставите всем получателям копию настоящей Лицензии вместе с Программой.

   Вы можете взимать любую плату или не взимать плату за каждую передаваемую копию, а также можете предлагать поддержку или гарантийную защиту за плату.

   5. Передача модифицированных версий исходного кода.

   Вы можете передавать произведение, основанное на Программе, или модификации для его создания из Программы в форме исходного кода на условиях Раздела 4, при условии, что вы также соблюдаете все следующие условия:

     a) Произведение должно содержать заметные уведомления о том, что вы изменили его, с указанием соответствующей даты.

     b) Произведение должно содержать заметные уведомления о том, что оно выпущено в соответствии с настоящей Лицензией и любыми условиями, добавленными в соответствии с Разделом 7. Это требование изменяет требование Раздела 4 «сохранять в неприкосновенности все уведомления».

     c) Вы должны лицензировать все произведение в целом в соответствии с настоящей Лицензией каждому, кто становится владельцем копии. Таким образом, настоящая Лицензия будет применяться вместе с любыми применимыми дополнительными условиями Раздела 7 ко всему произведению и ко всем его частям, независимо от того, как они упакованы. Настоящая Лицензия не дает разрешения на лицензирование произведения каким-либо иным образом, но не аннулирует такое разрешение, если вы получили его отдельно.

     d) Если произведение имеет интерактивные пользовательские интерфейсы, каждый из них должен отображать Соответствующие юридические уведомления; однако, если Программа имеет интерактивные интерфейсы, которые не отображают Соответствующие юридические уведомления, ваше произведение не обязано заставлять их делать это.

   Сборник покрываемого произведения с другими отдельными и независимыми произведениями, которые по своей природе не являются расширениями покрываемого произведения и не объединены с ним таким образом, чтобы сформировать более крупную программу, на носителе данных или распространения, называется «агрегатором» (aggregate), если компиляция и полученные в результате авторские права не используются для ограничения доступа или юридических прав пользователей сборника сверх того, что допускают отдельные произведения. Включение покрываемого произведения в агрегатор не приводит к распространению действия настоящей Лицензии на другие части агрегатора.

   6. Передача в неисходных формах (Объектный код / Бинарные сборки APK).

   Вы можете передавать покрываемое произведение в форме объектного кода на условиях Разделов 4 и 5 при условии, что вы также передаете машиночитаемый Соответствующий исходный код на условиях настоящей Лицензии одним из следующих способов:

     a) Передать объектный код в физическом продукте (включая физический носитель распространения) в сопровождении Соответствующего исходного кода, записанного на прочном физическом носителе, обычно используемом для обмена программным обеспечением.

     b) Передать объектный код в физическом продукте (включая физический носитель распространения) в сопровождении письменного предложения, действительного не менее трех лет и действительного до тех пор, пока вы предлагаете запасные части или поддержку пользователей для этой модели продукта, предоставить любому владельцу объектного кода либо (1) копию Соответствующего исходного кода для всего программного обеспечения в продукте, защищенного настоящей Лицензией, на прочном физическом носителе, обычно используемом для обмена ПО, по цене не выше ваших разумных затрат на физическое осуществление этой передачи исходного кода, либо (2) бесплатный доступ к копированию Соответствующего исходного кода с сетевого сервера.

     c) Передать отдельные копии объектного кода с копией письменного предложения о предоставлении Соответствующего исходного кода. Эта альтернатива допускается только изредка и в некоммерческих целях, и только если вы получили объектный код с таким предложением в соответствии с подразделом 6b.

     d) Передать объектный код путем предложения доступа из определенного места (бесплатно или за плату) и предложить эквивалентный доступ к Соответствующему исходному коду таким же образом через то же место без дополнительной платы. Вы не обязаны требовать от получателей копирования Соответствующего исходного кода вместе с объектным кодом. Если местом для копирования объектного кода является сетевой сервер, Соответствующий исходный код может находиться на другом сервере (управляемом вами или третьей стороной), поддерживающем эквивалентные возможности копирования, при условии, что вы поддерживаете четкие указания рядом с объектным кодом о том, где найти Соответствующий исходный код. Независимо от того, какой сервер размещает Соответствующий исходный код, вы обязаны гарантировать его доступность до тех пор, пока это необходимо для удовлетворения этих требований.

     e) Передать объектный код с использованием одноранговой передачи (peer-to-peer), при условии, что вы проинформируете других участников о том, где объектный код и Соответствующий исходный код произведения предлагаются общественности бесплатно в соответствии с подразделом 6d.

   Отделимая часть объектного кода, исходный код которой исключен из Соответствующего исходного кода как Системная библиотека, не требуется включать при передаче произведения в форме объектного кода.

   «Пользовательский продукт» — это либо (1) «потребительский продукт», что означает любое материальное личное имущество, которое обычно используется для личных, семейных или домашних целей, либо (2) все, что разработано или продается для встраивания в жилье. При определении того, является ли продукт потребительским, сомнительные случаи должны решаться в пользу охвата лицензией. Для конкретного продукта, полученного конкретным пользователем, «обычно используемый» относится к типичному или распространенному использованию этого класса продуктов, независимо от статуса конкретного пользователя или от того, как конкретный пользователь фактически использует, ожидает или предполагается использовать продукт. Продукт является потребительским продуктом независимо от того, имеет ли продукт существенное коммерческое, промышленное или непотребительское применение, если только такое применение не представляет собой единственный значительный способ использования продукта.

   «Информация об установке» для Пользовательского продукта означает любые методы, процедуры, ключи авторизации или другую информацию, необходимую для установки и выполнения модифицированных версий покрываемого произведения в этом Пользовательском продукте из модифицированной версии его Соответствующего исходного кода. Информация должна быть достаточной для того, чтобы гарантировать, что продолжению функционирования модифицированного объектного кода ни в коем случае не препятствуют и не мешают исключительно по причине внесения изменений.

   Если вы передаете произведение в форме объектного кода в соответствии с данным разделом в Пользовательском продукте, с ним или специально для использования в нем, и передача происходит как часть транзакции, при которой право владения и использования Пользовательского продукта передается получателю бессрочно или на фиксированный срок (независимо от того, как характеризуется транзакция), Соответствующий исходный код, передаваемый в соответствии с данным разделом, должен сопровождаться информацией об установке. Но это требование не применяется, если ни вы, ни какая-либо третья сторона не сохраняете возможность установки модифицированного объектного кода в Пользовательский продукт (например, произведение установлено в ПЗУ / ROM).

   Требование о предоставлении Информации об установке не включает требование продолжать предоставлять услуги поддержки, гарантию или обновления для произведения, которое было модифицировано или установлено получателем, или для Пользовательского продукта, в котором оно было модифицировано или установлено. В доступе к сети может быть отказано, если сама модификация существенно и неблагоприятно влияет на работу сети или нарушает правила и протоколы связи в сети.

   Соответствующий исходный код, передаваемый, и Информация об установке, предоставляемая в соответствии с этим разделом, должны быть в формате, который является общедоступным (и с реализацией, доступной общественности в форме исходного кода), и не должны требовать специального пароля или ключа для распаковки, чтения или копирования.

   7. Дополнительные условия.

   «Дополнительные разрешения» — это условия, которые дополняют условия настоящей Лицензии, делая исключения из одного или нескольких её условий. Дополнительные разрешения, применимые ко всей Программе, должны рассматриваться так, как если бы они были включены в настоящую Лицензию, в той мере, в какой они действительны в соответствии с применимым законодательством. Если дополнительные разрешения применяются только к части Программы, эта часть может использоваться отдельно в соответствии с этими разрешениями, но вся Программа остается под управлением настоящей Лицензии без учета дополнительных разрешений.

   Когда вы передаете копию покрываемого произведения, вы можете по своему усмотрению удалить любые дополнительные разрешения из этой копии или из любой её части. (Дополнительные разрешения могут быть написаны таким образом, чтобы требовать их собственного удаления в определенных случаях при модификации произведения). Вы можете поместить дополнительные разрешения на материал, добавленный вами к покрываемому произведению, на который у вас есть или вы можете дать соответствующее разрешение на авторские права.

   Несмотря на любые другие положения настоящей Лицензии, для материалов, которые вы добавляете к покрываемому произведению, вы можете (если это разрешено правообладателями этих материалов) дополнить условия настоящей Лицензии следующими условиями:

     a) Отказ от гарантий или ограничение ответственности иначе, чем на условиях Разделов 15 и 16 настоящей Лицензии; или

     b) Требование сохранения определенных разумных юридических уведомлений или указания авторства в этом материале или в Соответствующих юридических уведомлениях, отображаемых содержащими его произведениями; или

     c) Запрет на искажение происхождения этого материала или требование маркировки модифицированных версий такого материала разумными способами как отличающихся от оригинальной версии; или

     d) Ограничение использования в рекламных целях имен лицензиаров или авторов материала; или

     e) Отказ в предоставлении прав в соответствии с законом о товарных знаках на использование некоторых фирменных наименований, товарных знаков или знаков обслуживания; или

     f) Требование возмещения убытков лицензиарам и авторам этого материала любым лицом, передающим материал (или его модифицированные версии) со взятием на себя договорных обязательств по ответственности перед получателем, за любую ответственность, которую эти договорные обязательства непосредственно возлагают на этих лицензиаров и авторов.

   Все остальные неразрешительные дополнительные условия считаются «дальнейшими ограничениями» по смыслу Раздела 10. Если полученная вами Программа или любая её часть содержит уведомление о том, что она регулируется настоящей Лицензией вместе с условием, являющимся дальнейшим ограничением, вы можете удалить это условие. Если лицензионный документ содержит дальнейшее ограничение, но допускает повторное лицензирование или передачу в соответствии с настоящей Лицензией, вы можете добавить к покрываемому произведению материал, регулируемый условиями этого лицензионного документа, при условии, что дальнейшее ограничение не сохранится при таком повторном лицензировании или передаче.

   Если вы добавляете условия к покрываемому произведению в соответствии с этим разделом, вы должны поместить в соответствующие исходные файлы заявление о дополнительных условиях, применяемых к этим файлам, или уведомление с указанием места, где можно найти применимые условия.

   Дополнительные условия, разрешительные или неразрешительные, могут быть изложены в форме отдельно написанной лицензии или в виде исключений; вышеуказанные требования применяются в любом случае.

   8. Прекращение действия лицензии.

   Вы не можете распространять или передавать покрываемое произведение, за исключением случаев, явно предусмотренных настоящей Лицензией. Любая попытка распространить или передать его иным образом является недействительной и автоматически прекращает ваши права по настоящей Лицензии (включая любые патентные лицензии, предоставленные в соответствии с третьим абзацем Раздела 11).

   Однако, если вы прекратите все нарушения настоящей Лицензии, ваша лицензия от конкретного правообладателя восстанавливается (a) временно, пока правообладатель явно и окончательно не прекратит действие вашей лицензии, и (b) постоянно, если правообладатель не уведомит вас о нарушении разумным способом до истечения 60 дней после прекращения нарушения.

   Кроме того, ваша лицензия от конкретного правообладателя восстанавливается постоянно, если правообладатель уведомляет вас о нарушении разумным способом, вы впервые получили уведомление о нарушении настоящей Лицензии (для любого произведения) от этого правообладателя, и вы устраняете нарушение до истечения 30 дней после получения уведомления.

   Прекращение ваших прав в соответствии с этим разделом не прекращает действие лицензий лиц, получивших копии или права от вас по настоящей Лицензии. Если ваши права были прекращены и не восстановлены постоянно, вы не имеете права на получение новых лицензий на тот же материал в соответствии с Разделом 10.

   9. Принятие лицензии не требуется для владения копиями.

   Вы не обязаны принимать настоящую Лицензию для того, чтобы получить или запустить копию Программы. Вспомогательное распространение покрываемого произведения, происходящее исключительно в результате использования одноранговой передачи (P2P) для получения копии, аналогично не требует принятия. Однако ничто, кроме настоящей Лицензии, не дает вам разрешения распространять или передавать любое покрываемое произведение. Эти действия нарушают авторские права, если вы не принимаете настоящую Лицензию. Таким образом, изменяя или распространяя покрываемое произведение, вы выражаете свое согласие с настоящей Лицензией на совершение этих действий.

   10. Автоматическое лицензирование последующих получателей.

   Каждый раз, когда вы передаете покрываемое произведение, получатель автоматически получает лицензию от оригинальных лицензиаров на запуск, изменение и распространение этого произведения в соответствии с настоящей Лицензией. Вы не несете ответственности за обеспечение соблюдения третьими лицами настоящей Лицензии.

   «Сделка с хозяйствующим субъектом» — это сделка по передаче контроля над организацией, или существенной части ее активов, или разделению организации, или слиянию организаций. Если распространение покрываемого произведения является результатом сделки с хозяйствующим субъектом, каждая сторона этой сделки, получившая копию произведения, также получает любые лицензии на произведение, которые имел или мог предоставить ее правопредшественник в соответствии с предыдущим абзацем, плюс право на владение Соответствующим исходным кодом произведения от правопредшественника, если правопредшественник имеет его или может получить с помощью разумных усилий.

   Вы не можете налагать какие-либо дальнейшие ограничения на осуществление прав, предоставленных или подтвержденных настоящей Лицензией. Например, вы не можете взимать лицензионный сбор, роялти или иную плату за осуществление прав, предоставленных настоящей Лицензией, и вы не можете инициировать судебные разбирательства (включая встречный иск в судебном процессе), утверждая, что какой-либо патентный иск нарушен путем изготовления, использования, продажи, предложения к продаже или импорта Программы или любой её части.

   11. Патенты.

   «Контрибьютор» — это правообладатель, который разрешает использование в соответствии с настоящей Лицензией Программы или произведения, на котором основана Программа. Лицензированное таким образом произведение называется «версией контрибьютора».

   «Существенные патентные притязания» контрибьютора — это все патентные притязания, принадлежащие или контролируемые контрибьютором, независимо от того, приобретены ли они ранее или впоследствии, которые были бы нарушены каким-либо способом, разрешенным настоящей Лицензией, изготовления, использования или продажи версии контрибьютора, но не включают притязания, которые были бы нарушены только в результате дальнейшей модификации версии контрибьютора. Для целей этого определения «контроль» включает право предоставлять патентные сублицензии способом, соответствующим требованиям настоящей Лицензии.

   Каждый контрибьютор предоставляет вам неисключительную, всемирную, безвозмездную патентную лицензию на основе существенных патентных притязаний контрибьютора на изготовление, использование, продажу, предложение к продаже, импорт и иное выполнение, изменение и распространение содержимого его версии контрибьютора.

   В следующих трех абзацах «патентная лицензия» означает любое явное соглашение или обязательство, как бы оно ни именовалось, не обеспечивать соблюдение патента (например, явное разрешение на использование патента или обязательство не подавать иск за нарушение патента). «Предоставить» такую патентную лицензию стороне означает заключить такое соглашение или обязательство не применять патент в отношении этой стороны.

   Если вы передаете покрываемое произведение, сознательно опираясь на патентную лицензию, и Соответствующий исходный код произведения недоступен никому для копирования, бесплатно и на условиях настоящей Лицензии, через общедоступный сетевой сервер или другие легкодоступные средства, вы должны либо (1) сделать Соответствующий исходный код так же доступным, либо (2) лишить себя выгоды от патентной лицензии для этого конкретного произведения, либо (3) организовать способом, соответствующим требованиям настоящей Лицензии, распространение патентной лицензии на последующих получателей. «Сознательное опирание» означает, что вы обладаете фактическим знанием того, что без патентной лицензии ваша передача покрываемого произведения в стране или использование покрываемого произведения вашим получателем в стране нарушило бы один или несколько идентифицируемых патентов в этой стране, которые вы имеете основания считать действительными.

   Если в соответствии со сдельной операцией или соглашением или в связи с ними вы передаете или способствуете передаче покрываемого произведения и предоставляете патентную лицензию некоторым из сторон, получающих покрываемое произведение, уполномочивающую их использовать, распространять, модифицировать или передавать конкретную копию покрываемого произведения, то предоставляемая вами патентная лицензия автоматически распространяется на всех получателей покрываемого произведения и произведений, основанных на нем.

   Патентная лицензия является «дискриминационной», если она не включает в сферу своего действия, запрещает осуществление или обуславливает неосуществление одного или нескольких прав, специально предоставленных настоящей Лицензией. Вы не можете передавать покрываемое произведение, если вы являетесь стороной соглашения с третьей стороной, занимающейся распространением программного обеспечения, по которому вы производите платежи третьей стороне в зависимости от объема вашей деятельности по передаче произведения, и по которому третья сторона предоставляет любому из лиц, которые получили бы от вас покрываемое произведение, дискриминационную патентную лицензию (a) в связи с копиями покрываемого произведения, передаваемыми вами (или копиями, сделанными из этих копий), или (b) в основном для конкретных продуктов или сборников, содержащих покрываемое произведение и в связи с ними, если только вы не заключили это соглашение или эта патентная лицензия не была предоставлена до 28 марта 2007 года.

   Ничто в настоящей Лицензии не должно толковаться как исключающее или ограничивающее любую подразумеваемую лицензию или другие средства защиты от нарушения, которые в противном случае могут быть доступны вам в соответствии с применимым патентным законодательством.

   12. Недопустимость отказа от свободы других лиц.

   Если на вас возлагаются условия (будь то по решению суда, соглашению или иным образом), которые противоречат условиям настоящей Лицензии, они не освобождают вас от условий настоящей Лицензии. Если вы не можете передать покрываемое произведение так, чтобы одновременно удовлетворять своим обязательствам по настоящей Лицензии и любым другим соответствующим обязательствам, то в результате вы вообще не можете передавать его. Например, если вы соглашаетесь с условиями, которые обязывают вас собирать роялти за передачу от тех, кому вы передаете Программу, единственный способ одновременно удовлетворить этим условиям и настоящей Лицензии — полностью воздержаться от передачи Программы.

   13. Использование совместно с GNU Affero General Public License.

   Несмотря на любые другие положения настоящей Лицензии, вы имеете разрешение связывать или объединять любое покрываемое произведение с произведением, лицензированным в соответствии с версией 3 GNU Affero General Public License, в единое комбинированное произведение и передавать полученное произведение. Условия настоящей Лицензии будут продолжать применяться к той части, которая является покрываемым произведением, но специальные требования GNU Affero General Public License, Раздел 13, касающиеся взаимодействия через сеть, будут применяться к комбинации как таковой.

   14. Новые редакции настоящей Лицензии.

   Фонд свободного программного обеспечения (Free Software Foundation) может время от времени публиковать измененные и/или новые версии Стандартной общественной лицензии GNU. Такие новые версии будут аналогичны по духу настоящей версии, но могут отличаться в деталях для решения новых проблем или задач.

   Каждой версии присваивается отличительный номер версии. Если в Программе указано, что к ней применяется определенная пронумерованная версия Стандартной общественной лицензии GNU «или любая более поздняя версия», вы можете следовать условиям и положениям либо этой пронумерованной версии, либо любой более поздней версии, опубликованной Фондом свободного программного обеспечения. Если в Программе не указан номер версии Стандартной общественной лицензии GNU, вы можете выбрать любую версию, когда-либо опубликованную Фондом свободного программного обеспечения.

   Если в Программе указано, что доверенное лицо (proxy) может решать, какие будущие версии Стандартной общественной лицензии GNU могут использоваться, публичное заявление этого доверенного лица о принятии версии навсегда дает вам право выбрать эту версию для Программы.

   Более поздние версии лицензии могут давать вам дополнительные или иные разрешения. Однако на любого автора или разработчика не налагаются никакие дополнительные обязательства в результате вашего решения следовать более поздней версии.

   15. Отказ от гарантий.

   ПРОГРАММНОЕ ОБЕСПЕЧЕНИЕ ПРЕДОСТАВЛЯЕТСЯ «КАК ЕСТЬ» (AS IS), В МАКСИМАЛЬНОЙ СТЕПЕНИ, ДОПУСТИМОЙ ПРИМЕНИМЫМ ЗАКОНОДАТЕЛЬСТВОМ. ЕСЛИ ИНОЕ НЕ УКАЗАНО В ПИСЬМЕННОЙ ФОРМЕ, ОБЛАДАТЕЛИ АВТОРСКИХ ПРАВ И/ИЛИ ДРУГИЕ СТОРОНЫ ПРЕДОСТАВЛЯЮТ ПРОГРАММУ «КАК ЕСТЬ» БЕЗ КАКИХ-ЛИБО ГАРАНТИЙ, ЯВНЫХ ИЛИ ПОДРАЗУМЕВАЕМЫХ, ВКЛЮЧАЯ, НО НЕ ОГРАНИЧИВАЯСЬ, ПОДРАЗУМЕВАЕМЫМИ ГАРАНТИЯМИ ТОВАРНОЙ ПРИГОДНОСТИ И СООТВЕТСТВИЯ ОПРЕДЕЛЕННОЙ ЦЕЛИ. ВЕСЬ РИСК, КАСАЮЩИЙСЯ КАЧЕСТВА И РАБОТОСПОСОБНОСТИ ПРОГРАММЫ, ЛЕЖИТ НА ВАС. ЕСЛИ В ПРОГРАММЕ ОБНАРУЖИТСЯ ДЕФЕКТ, ВЫ БЕРЕТЕ НА СЕБЯ РАСХОДЫ НА ВСЕ НЕОБХОДИМОЕ ОБСЛУЖИВАНИЕ, РЕМОНТ ИЛИ ИСПРАВЛЕНИЕ.

   16. Ограничение ответственности.

   НИ ПРИ КАКИХ ОБСТОЯТЕЛЬСТВАХ, ЕСЛИ ЭТОГО НЕ ТРЕБУЕТ ПРИМЕНИМОЕ ЗАКОНОДАТЕЛЬСТВО ИЛИ НЕ СОГЛАСОВАНО В ПИСЬМЕННОЙ ФОРМЕ, НИ ОДИН ОБЛАДАТЕЛЬ АВТОРСКИХ ПРАВ ИЛИ ЛЮБАЯ ДРУГАЯ СТОРОНА, КОТОРАЯ МОДИФИЦИРУЕТ И/ИЛИ ПЕРЕДАЕТ ПРОГРАММУ, КАК РАЗРЕШЕНО ВЫШЕ, НЕ НЕСЕТ ОТВЕТСТВЕННОСТИ ПЕРЕД ВАМИ ЗА УБЫТКИ, ВКЛЮЧАЯ ЛЮБЫЕ ОБЩИЕ, ФАКТИЧЕСКИЕ, СЛУЧАЙНЫЕ ИЛИ КОСВЕННЫЕ УБЫТКИ, ВОЗНИКШИЕ В РЕЗУЛЬТАТЕ ИСПОЛЬЗОВАНИЯ ИЛИ НЕВОЗМОЖНОСТИ ИСПОЛЬЗОВАНИЯ ПРОГРАММЫ (ВКЛЮЧАЯ, НО НЕ ОГРАНИЧИВАЯСЬ ПОТЕРЕЙ ДАННЫХ ИЛИ ИСКАЖЕНИЕМ ДАННЫХ, УБЫТКАМИ, ПОНЕСЕННЫМИ ВАМИ ИЛИ ТРЕТЬИМИ ЛИЦАМИ, ИЛИ СБОЕМ ПРОГРАММЫ ПРИ РАБОТЕ С ЛЮБЫМИ ДРУГИМИ ПРОГРАММАМИ), ДАЖЕ ЕСЛИ ТАКОЙ ОБЛАДАТЕЛЬ ИЛИ ДРУГАЯ СТОРОНА БЫЛИ УВЕДОМЛЕНЫ О ВОЗМОЖНОСТИ ТАКИХ УБЫТКОВ.

   17. Толкование разделов 15 и 16.

   Если отказ от гарантий и ограничение ответственности, приведенные выше, не могут иметь местной юридической силы в соответствии с их условиями, суды должны применять местное законодательство, наиболее близкое к абсолютному отказу от любой гражданской ответственности в связи с Программой, если только гарантия или принятие ответственности не сопровождают копию Программы в обмен на плату.

                     КОНЕЦ УСЛОВИЙ И ПОЛОЖЕНИЙ

   Авторские права (C) 2026 R1Xern (Mirrly Dev)

   Mirrly TG Proxy является свободным программным обеспечением: вы можете распространять его и/или модифицировать на условиях Стандартной общественной лицензии GNU (GNU General Public License) в том виде, в каком она была опубликована Фондом свободного программного обеспечения; либо версии 3 Лицензии, либо (по вашему выбору) любой более поздней версии.

   Эта программа распространяется в надежде, что она будет полезной, но БЕЗ КАКИХ-ЛИБО ГАРАНТИЙ; даже без подразумеваемой гарантии ТОВАРНОЙ ПРИГОДНОСТИ или СООТВЕТСТВИЯ ОПРЕДЕЛЕННОЙ ЦЕЛИ. Для получения подробной информации см. Стандартную общественную лицензию GNU.

   Вы должны были получить копию Стандартной общественной лицензии GNU вместе с этой программой. Если нет, см. <https://www.gnu.org/licenses/>.
        """.trimIndent()
    }

    val activeLicenseText = if (selectedLanguage == "ru") fullLicenseTextRu else fullLicenseTextEn
    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }

    if (pendingRedirectUrl != null) {
        ExternalLinkConfirmDialog(
            url = pendingRedirectUrl ?: "",
            onDismiss = { pendingRedirectUrl = null }
        )
    }

    fun openGitHubLicense() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        pendingRedirectUrl = "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/blob/main/LICENSE"
    }

    fun copyLicenseToClipboard() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val label = if (selectedLanguage == "ru") "Лицензия GNU GPLv3 (Русский)" else "GNU GPLv3 License (English)"
        val clip = ClipData.newPlainText(label, activeLicenseText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Текст лицензии скопирован!", Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. SCROLLABLE CONTENT LAYER
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(topFadeHeight = 24.dp, bottomFadeHeight = 44.dp)
                .verticalScroll(rememberScrollState())
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
                )
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {

            // SECTION 1: HEADER CARD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredEntrance(index = 0)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Transparent)
                    .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ActiveGreenLed.copy(alpha = 0.15f))
                                .border(1.5.dp, ActiveGreenLed.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_license),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "GNU GPLv3 License",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = TextWhite
                            )
                            Text(
                                text = "Copyright (c) 2026 R1Xern (Mirrly Dev)",
                                fontSize = 12.5.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Text(
                        text = "Данное приложение является свободным программным обеспечением под защитой лицензии GNU GPLv3. Все производные работы и форки обязаны сохранять исходный код открытым.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = TextWhite.copy(alpha = 0.85f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { openGitHubLicense() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = ActiveGreenLed
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ActiveGreenLed),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = null,
                                tint = ActiveGreenLed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("GitHub", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = { copyLicenseToClipboard() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF141A29),
                                contentColor = TextWhite
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F283D)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
                                contentDescription = null,
                                tint = TextWhite,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Скопировать", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // SECTION 2: PERMISSIONS SUMMARY
            Column(
                modifier = Modifier.staggeredEntrance(index = 1),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ПРАВА И УСЛОВИЯ (GPLv3)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.3.sp,
                    color = TextMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Transparent)
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PermissionRow(text = "Разрешено свободное использование и распространение")
                        PermissionRow(text = "Разрешена модификация исходного кода")
                        PermissionRow(text = "Защита Copyleft: Запрещено закрывать код в форках")
                        PermissionRow(text = "Защита от патентных исков и блокировки устройств")
                        HorizontalDivider(color = Color(0xFF161A26), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "Обязательное условие: Сохранение указания авторства, копилефт-лицензии GPLv3 и открытого исходного кода во всех производных работах.",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            // SECTION 3: FULL LICENSE TEXT CODE BLOCK WITH LANGUAGE TAB SELECTOR
            Column(
                modifier = Modifier.staggeredEntrance(index = 2),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ТЕКСТ ЛИЦЕНЗИИ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.3.sp,
                        color = TextMuted
                    )

                    Text(
                        text = if (selectedLanguage == "ru") "Русский" else "English",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ActiveGreenLed
                    )
                }

                // Smooth Segmented Tab Switcher (Russian / English)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0D111A))
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isRu = selectedLanguage == "ru"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isRu) ActiveGreenLed.copy(alpha = 0.18f) else Color.Transparent)
                            .border(if (isRu) 1.dp else 0.dp, if (isRu) ActiveGreenLed.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedLanguage = "ru"
                            }
                    ) {
                        Text(
                            text = "Русский",
                            fontSize = 12.5.sp,
                            fontWeight = if (isRu) FontWeight.Bold else FontWeight.Medium,
                            color = if (isRu) ActiveGreenLed else TextMuted
                        )
                    }

                    val isEn = selectedLanguage == "en"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isEn) ActiveGreenLed.copy(alpha = 0.18f) else Color.Transparent)
                            .border(if (isEn) 1.dp else 0.dp, if (isEn) ActiveGreenLed.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedLanguage = "en"
                            }
                    ) {
                        Text(
                            text = "English",
                            fontSize = 12.5.sp,
                            fontWeight = if (isEn) FontWeight.Bold else FontWeight.Medium,
                            color = if (isEn) ActiveGreenLed else TextMuted
                        )
                    }
                }

                // License Text Display Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF080B12))
                        .border(1.dp, Color(0xFF181E2E), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Crossfade(targetState = selectedLanguage, animationSpec = tween(220), label = "licenseCrossfade") { lang ->
                        val textToDisplay = if (lang == "ru") fullLicenseTextRu else fullLicenseTextEn
                        Text(
                            text = textToDisplay,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextWhite.copy(alpha = 0.88f),
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. FROSTED GLASS HEADER PANEL
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.94f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.00f)
                        )
                    )
                )
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Лицензия GNU GPLv3",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_left),
                            contentDescription = "Назад",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    }
}

@Composable
private fun PermissionRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(ActiveGreenLed.copy(alpha = 0.2f))
        ) {
            Text(
                text = "✓",
                color = ActiveGreenLed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextWhite
        )
    }
}
